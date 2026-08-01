package com.insula.download;

import java.util.ArrayList;
import java.util.List;

/**
 * How a file is split for multi-source download, and which parts are already on disk.
 *
 * <p>Chunks are aligned to the Metalink piece size when the sidecar has piece hashes, so a chunk
 * is a whole number of pieces and can be verified the moment it lands. Without piece hashes the
 * file is split into a fixed number of chunks instead.
 *
 * <p>Pure and unit-tested: the arithmetic here decides where bytes are written, so an off-by-one
 * corrupts the file rather than merely slowing it down.
 */
public final class ChunkPlan {

    /** Pieces per chunk when piece hashes exist: 4 MiB × 4 = 16 MiB of work per request. */
    static final int PIECES_PER_CHUNK = 4;
    /** Chunk size used when the file has no piece hashes. */
    static final long DEFAULT_CHUNK_BYTES = 16L * 1024 * 1024;

    /**
     * One contiguous byte range to fetch.
     *
     * @param end exclusive
     * @param firstPiece index of the first Metalink piece in this chunk, or -1 when unverifiable
     */
    public record Chunk(int index, long start, long end, int firstPiece) {
        public long length() {
            return end - start;
        }

        /** HTTP Range header value for this chunk (inclusive bounds). */
        public String rangeHeader() {
            return "bytes=" + start + "-" + (end - 1);
        }
    }

    private final long fileSize;
    private final List<Chunk> chunks;
    private final boolean[] done;

    private ChunkPlan(long fileSize, List<Chunk> chunks) {
        this.fileSize = fileSize;
        this.chunks = List.copyOf(chunks);
        this.done = new boolean[chunks.size()];
    }

    public static ChunkPlan forFile(long fileSize, long pieceLength, int pieceCount) {
        if (fileSize <= 0) {
            throw new IllegalArgumentException("File size must be positive: " + fileSize);
        }
        List<Chunk> chunks = new ArrayList<>();
        boolean piecewise = pieceLength > 0 && pieceCount > 0;
        long chunkBytes = piecewise ? pieceLength * PIECES_PER_CHUNK : DEFAULT_CHUNK_BYTES;

        long start = 0;
        int index = 0;
        while (start < fileSize) {
            long end = Math.min(start + chunkBytes, fileSize);
            int firstPiece = piecewise ? (int) (start / pieceLength) : -1;
            chunks.add(new Chunk(index++, start, end, firstPiece));
            start = end;
        }
        return new ChunkPlan(fileSize, chunks);
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public int size() {
        return chunks.size();
    }

    public long fileSize() {
        return fileSize;
    }

    public boolean isDone(int index) {
        return done[index];
    }

    public void markDone(int index) {
        done[index] = true;
    }

    public boolean isComplete() {
        for (boolean b : done) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    public long completedBytes() {
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (done[i]) {
                total += chunks.get(i).length();
            }
        }
        return total;
    }

    public List<Chunk> pending() {
        List<Chunk> out = new ArrayList<>();
        for (Chunk c : chunks) {
            if (!done[c.index()]) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Serializes the completion bitmap as ASCII {@code 0}/{@code 1}, one char per chunk — written
     * beside the partial file so an interrupted download resumes instead of restarting.
     */
    public String toBitmap() {
        StringBuilder sb = new StringBuilder(done.length);
        for (boolean b : done) {
            sb.append(b ? '1' : '0');
        }
        return sb.toString();
    }

    /**
     * Restores completion state. A bitmap whose length disagrees with this plan is ignored
     * entirely — it describes a different file (or a different chunking), and trusting it would
     * mark never-downloaded ranges as present and silently produce a corrupt archive.
     */
    public boolean applyBitmap(String bitmap) {
        if (bitmap == null || bitmap.length() != done.length) {
            return false;
        }
        for (int i = 0; i < bitmap.length(); i++) {
            char c = bitmap.charAt(i);
            if (c != '0' && c != '1') {
                return false;
            }
        }
        for (int i = 0; i < bitmap.length(); i++) {
            done[i] = bitmap.charAt(i) == '1';
        }
        return true;
    }
}
