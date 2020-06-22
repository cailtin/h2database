/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.PriorityQueue;

/**
 * Class RandomAccessStore.
 * <UL>
 * <LI> 4/5/20 2:51 PM initial creation
 * </UL>
 *
 * @author <a href='mailto:andrei.tokar@gmail.com'>Andrei Tokar</a>
 */
public abstract class RandomAccessStore extends FileStore {
    /**
     * The free spaces between the chunks. The first block to use is block 2
     * (the first two blocks are the store header).
     */
    protected final FreeSpaceBitSet freeSpace = new FreeSpaceBitSet(2, BLOCK_SIZE);

    /**
     * Allocation mode:
     * false - new chunk is always allocated at the end of file
     * true - new chunk is allocated as close to the begining of file, as possible
     */
    private volatile boolean reuseSpace = true;

    private long reservedLow;
    private long reservedHigh;



    public RandomAccessStore() {
        super();
    }

    /**
     * Mark the space as in use.
     *
     * @param pos the position in bytes
     * @param length the number of bytes
     */
    public void markUsed(long pos, int length) {
        freeSpace.markUsed(pos, length);
    }

    /**
     * Allocate a number of blocks and mark them as used.
     *
     * @param length the number of bytes to allocate
     * @param reservedLow start block index of the reserved area (inclusive)
     * @param reservedHigh end block index of the reserved area (exclusive),
     *                     special value -1 means beginning of the infinite free area
     * @return the start position in bytes
     */
    private long allocate(int length, long reservedLow, long reservedHigh) {
        return freeSpace.allocate(length, reservedLow, reservedHigh);
    }

    /**
     * Calculate starting position of the prospective allocation.
     *
     * @param blocks the number of blocks to allocate
     * @param reservedLow start block index of the reserved area (inclusive)
     * @param reservedHigh end block index of the reserved area (exclusive),
     *                     special value -1 means beginning of the infinite free area
     * @return the starting block index
     */
    private long predictAllocation(int blocks, long reservedLow, long reservedHigh) {
        return freeSpace.predictAllocation(blocks, reservedLow, reservedHigh);
    }

    boolean isFragmented() {
        return freeSpace.isFragmented();
    }

    public boolean isSpaceReused() {
        return reuseSpace;
    }

    public void setReuseSpace(boolean reuseSpace) {
        this.reuseSpace = reuseSpace;
    }

    /**
     * Mark the space as free.
     *
     * @param pos the position in bytes
     * @param length the number of bytes
     */
    public void free(long pos, int length) {
        freeSpace.free(pos, length);
    }

    public int getFillRate() {
        saveChunkLock.lock();
        try {
            return freeSpace.getFillRate();
        } finally {
            saveChunkLock.unlock();
        }
    }

    /**
     * Calculates a prospective fill rate, which store would have after rewrite
     * of sparsely populated chunk(s) and evacuation of still live data into a
     * new chunk.
     *
     * @param vacatedBlocks
     *            number of blocks vacated
     * @return prospective fill rate (0 - 100)
     */
    public int getProjectedFillRate(int vacatedBlocks) {
        return freeSpace.getProjectedFillRate(vacatedBlocks);
    }

    long getFirstFree() {
        return freeSpace.getFirstFree();
    }

    long getFileLengthInUse() {
        return freeSpace.getLastFree();
    }

    protected void allocateChunkSpace(Chunk c, WriteBuffer buff) {
        saveChunkLock.lock();
        try {
            int headerLength = (int)c.next;
            long reservedLow = this.reservedLow;
            long reservedHigh = this.reservedHigh > 0 ? this.reservedHigh : isSpaceReused() ? 0 : getAfterLastBlock();
            long filePos = allocate(buff.limit(), reservedLow, reservedHigh);
            // calculate and set the likely next position
            if (reservedLow > 0 || reservedHigh == reservedLow) {
                c.next = predictAllocation(c.len, 0, 0);
            } else {
                // just after this chunk
                c.next = 0;
            }
//            assert c.pageCountLive == c.pageCount : c;
//            assert c.occupancy.cardinality() == 0 : c;

            buff.position(0);
            c.writeChunkHeader(buff, headerLength);

            buff.position(buff.limit() - Chunk.FOOTER_LENGTH);
            buff.put(c.getFooterBytes());

            c.block = filePos / BLOCK_SIZE;
            assert validateFileLength(c.asString());
        } finally {
            saveChunkLock.unlock();
        }
    }

/*
    public void compactFile(int tresholdFildRate, long maxCompactTime, long maxWriteSize, MVStore mvStore) {
        long start = System.nanoTime();
        while (compact(tresholdFildRate, maxWriteSize)) {
            sync();
            compactMoveChunks(tresholdFildRate, maxWriteSize);
            long time = System.nanoTime() - start;
            if (time > TimeUnit.MILLISECONDS.toNanos(maxCompactTime)) {
                break;
            }
        }
    }
*/

    public boolean compactMoveChunks(long moveSize, MVStore mvStore) {
        long start = getFirstFree() / FileStore.BLOCK_SIZE;
        Iterable<Chunk> chunksToMove = findChunksToMove(start, moveSize);
        if (chunksToMove != null) {
            compactMoveChunks(chunksToMove, mvStore);
            return true;
        }
        return false;
    }

    private Iterable<Chunk> findChunksToMove(long startBlock, long moveSize) {
        long maxBlocksToMove = moveSize / FileStore.BLOCK_SIZE;
        Iterable<Chunk> result = null;
        if (maxBlocksToMove > 0) {
            PriorityQueue<Chunk> queue = new PriorityQueue<>(getChunks().size() / 2 + 1,
                    (o1, o2) -> {
                        // instead of selection just closest to beginning of the file,
                        // pick smaller chunk(s) which sit in between bigger holes
                        int res = Integer.compare(o2.collectPriority, o1.collectPriority);
                        if (res != 0) {
                            return res;
                        }
                        return Long.signum(o2.block - o1.block);
                    });
            long size = 0;
            for (Chunk chunk : getChunks().values()) {
                if (chunk.isSaved() && chunk.block > startBlock) {
                    chunk.collectPriority = getMovePriority(chunk);
                    queue.offer(chunk);
                    size += chunk.len;
                    while (size > maxBlocksToMove) {
                        Chunk removed = queue.poll();
                        if (removed == null) {
                            break;
                        }
                        size -= removed.len;
                    }
                }
            }
            if (!queue.isEmpty()) {
                ArrayList<Chunk> list = new ArrayList<>(queue);
                list.sort(Chunk.PositionComparator.INSTANCE);
                result = list;
            }
        }
        return result;
    }

    private int getMovePriority(Chunk chunk) {
        return getMovePriority((int)chunk.block);
    }

    private void compactMoveChunks(Iterable<Chunk> move, MVStore mvStore) {
        assert saveChunkLock.isHeldByCurrentThread();
        if (move != null) {
            // this will ensure better recognition of the last chunk
            // in case of power failure, since we are going to move older chunks
            // to the end of the file
            writeStoreHeader();
            sync();

            Iterator<Chunk> iterator = move.iterator();
            assert iterator.hasNext();
            long leftmostBlock = iterator.next().block;
            long originalBlockCount = getAfterLastBlock();
            // we need to ensure that chunks moved within the following loop
            // do not overlap with space just released by chunks moved before them,
            // hence the need to reserve this area [leftmostBlock, originalBlockCount)
            for (Chunk chunk : move) {
                moveChunk(chunk, leftmostBlock, originalBlockCount, mvStore);
            }
            // update the metadata (hopefully within the file)
            store(leftmostBlock, originalBlockCount, mvStore);
            sync();

            Chunk chunkToMove = lastChunk;
            assert chunkToMove != null;
            long postEvacuationBlockCount = getAfterLastBlock();

            boolean chunkToMoveIsAlreadyInside = chunkToMove.block < leftmostBlock;
            boolean movedToEOF = !chunkToMoveIsAlreadyInside;
            // move all chunks, which previously did not fit before reserved area
            // now we can re-use previously reserved area [leftmostBlock, originalBlockCount),
            // but need to reserve [originalBlockCount, postEvacuationBlockCount)
            for (Chunk c : move) {
                if (c.block >= originalBlockCount &&
                        moveChunk(c, originalBlockCount, postEvacuationBlockCount, mvStore)) {
                    assert c.block < originalBlockCount;
                    movedToEOF = true;
                }
            }
            assert postEvacuationBlockCount >= getAfterLastBlock();

            if (movedToEOF) {
                boolean moved = moveChunkInside(chunkToMove, originalBlockCount, mvStore);

                // store a new chunk with updated metadata (hopefully within a file)
                store(originalBlockCount, postEvacuationBlockCount, mvStore);
                sync();
                // if chunkToMove did not fit within originalBlockCount (move is
                // false), and since now previously reserved area
                // [originalBlockCount, postEvacuationBlockCount) also can be
                // used, lets try to move that chunk into this area, closer to
                // the beginning of the file
                long lastBoundary = moved || chunkToMoveIsAlreadyInside ?
                                        postEvacuationBlockCount : chunkToMove.block;
                moved = !moved && moveChunkInside(chunkToMove, lastBoundary, mvStore);
                if (moveChunkInside(lastChunk, lastBoundary, mvStore) || moved) {
                    store(lastBoundary, -1, mvStore);
                }
            }

            shrinkStoreIfPossible(0);
            sync();
        }
    }

    private void store(long reservedLow, long reservedHigh, MVStore mvStore) {
        this.reservedLow = reservedLow;
        this.reservedHigh = reservedHigh;
        saveChunkLock.unlock();
        try {
            mvStore.store();
        } finally {
            saveChunkLock.lock();
            this.reservedLow = 0;
            this.reservedHigh = 0;
        }
    }

    private boolean moveChunkInside(Chunk chunkToMove, long boundary, MVStore mvStore) {
        boolean res = chunkToMove.block >= boundary &&
                predictAllocation(chunkToMove.len, boundary, -1) < boundary &&
                moveChunk(chunkToMove, boundary, -1, mvStore);
        assert !res || chunkToMove.block + chunkToMove.len <= boundary;
        return res;
    }

    /**
     * Move specified chunk into free area of the file. "Reserved" area
     * specifies file interval to be avoided, when un-allocated space will be
     * chosen for a new chunk's location.
     *
     * @param chunk to move
     * @param reservedAreaLow low boundary of reserved area, inclusive
     * @param reservedAreaHigh high boundary of reserved area, exclusive
     * @return true if block was moved, false otherwise
     */
    private boolean moveChunk(Chunk chunk, long reservedAreaLow, long reservedAreaHigh, MVStore mvStore) {
        // ignore if already removed during the previous store operations
        // those are possible either as explicit commit calls
        // or from meta map updates at the end of this method
        if (!getChunks().containsKey(chunk.id)) {
            return false;
        }
        long start = chunk.block * FileStore.BLOCK_SIZE;
        int length = chunk.len * FileStore.BLOCK_SIZE;
        long block;
        WriteBuffer buff = getWriteBuffer();
        try {
            buff.limit(length);
            ByteBuffer readBuff = readFully(start, length);
            Chunk chunkFromFile = Chunk.readChunkHeader(readBuff, start);
            int chunkHeaderLen = readBuff.position();
            buff.position(chunkHeaderLen);
            buff.put(readBuff);
            long pos = allocate(length, reservedAreaLow, reservedAreaHigh);
            block = pos / FileStore.BLOCK_SIZE;
            // in the absence of a reserved area,
            // block should always move closer to the beginning of the file
            assert reservedAreaHigh > 0 || block <= chunk.block : block + " " + chunk;
            buff.position(0);
            // also occupancy accounting fields should not leak into header
            chunkFromFile.block = block;
            chunkFromFile.next = 0;
            chunkFromFile.writeChunkHeader(buff, chunkHeaderLen);
            buff.position(length - Chunk.FOOTER_LENGTH);
            buff.put(chunkFromFile.getFooterBytes());
            buff.position(0);
            writeFully(pos, buff.getBuffer());
        } finally {
            releaseWriteBuffer(buff);
        }
        free(start, length);
        // can not set chunk's new block/len until it's fully written at new location,
        // because concurrent reader can pick it up prematurely,
        chunk.block = block;
        chunk.next = 0;
        mvStore.registerChunk(chunk);
        return true;
    }

    protected void shrinkIfPossible(int minPercent) {
        if (isReadOnly()) {
            return;
        }
        long end = getFileLengthInUse();
        long fileSize = size();
        if (end >= fileSize) {
            return;
        }
        if (minPercent > 0 && fileSize - end < BLOCK_SIZE) {
            return;
        }
        int savedPercent = (int) (100 - (end * 100 / fileSize));
        if (savedPercent < minPercent) {
            return;
        }
        sync();
        truncate(end);
    }

    protected abstract void truncate(long size);

    /**
     * Mark the file as empty.
     */
    @Override
    public void clear() {
        freeSpace.clear();
    }

    /**
     * Calculates relative "priority" for chunk to be moved.
     *
     * @param block where chunk starts
     * @return priority, bigger number indicate that chunk need to be moved sooner
     */
    public int getMovePriority(int block) {
        return freeSpace.getMovePriority(block);
    }

    protected long getAfterLastBlock_() {
        return freeSpace.getAfterLastBlock();
    }

    public Collection<Chunk> getRewriteCandidates() {
        return isSpaceReused() ? null : Collections.emptyList();
    }
}
