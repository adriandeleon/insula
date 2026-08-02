package com.insula.zim;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.luben.zstd.ZstdInputStream;
import org.tukaani.xz.XZInputStream;

/**
 * Reads blobs out of clusters. A cluster starts with one info byte — low nibble = compression
 * (0/1 = none, 4 = XZ/LZMA2, 5 = Zstandard), bit 0x10 = "extended" (u64 blob offsets instead of
 * u32). The (decompressed) body is a blob-offset table followed by the blob bytes; offsets are
 * relative to the body start, and {@code offset[0] / offsetSize - 1} gives the blob count.
 *
 * <p>Decompressed clusters are kept in a small bounded LRU cache so that serving an article and
 * its images (typically neighbors in the same cluster) decompresses once, not per blob.
 * Uncompressed clusters are never materialized — their blobs are pread directly from the file.
 */
final class ClusterStore {

    private static final int COMP_DEFAULT = 0;
    private static final int COMP_NONE = 1;
    private static final int COMP_XZ = 4;
    private static final int COMP_ZSTD = 5;

    /**
     * The cache is bounded by <b>decompressed bytes, not entry count</b>. Cluster sizes vary by
     * two orders of magnitude between archives, so a count-based bound is a bound in the wrong
     * dimension: 32 entries sounds modest but permits ~4 GB of retention at the per-cluster cap
     * below. This is a hard ceiling on what an open archive can hold.
     */
    static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    /** Safety cap on a single decompressed cluster (nominal cluster size is ~2 MB). */
    private static final int MAX_DECOMPRESSED = 128 * 1024 * 1024;

    private record Decoded(long[] offsets, byte[] data) {}

    private final LittleEndianFile in;
    private final ZimHeader header;

    /** Access-ordered; eviction is driven by {@link #cachedBytes}, see {@link #decodedCluster}. */
    private final Map<Long, Decoded> cache = new LinkedHashMap<>(16, 0.75f, true);

    private long cachedBytes;

    private final long maxCacheBytes;

    ClusterStore(LittleEndianFile in, ZimHeader header) {
        this(in, header, MAX_CACHE_BYTES);
    }

    /** Test seam: a small budget makes eviction observable without a multi-gigabyte archive. */
    ClusterStore(LittleEndianFile in, ZimHeader header, long maxCacheBytes) {
        this.in = in;
        this.header = header;
        this.maxCacheBytes = maxCacheBytes;
    }

    byte[] blob(long clusterNumber, long blobNumber) throws IOException {
        if (clusterNumber < 0 || clusterNumber >= header.clusterCount()) {
            throw new ZimFormatException("Cluster " + clusterNumber + " out of range");
        }
        long start = in.u64(header.clusterPtrPos() + 8 * clusterNumber);
        int info = in.u8(start);
        int compression = info & 0x0F;
        boolean extended = (info & 0x10) != 0;
        int offSize = extended ? 8 : 4;
        long body = start + 1;

        if (compression == COMP_DEFAULT || compression == COMP_NONE) {
            // Uncompressed: read the two offsets and pread just the blob.
            long first = offsetAt(body, 0, extended);
            long blobCount = first / offSize - 1;
            checkBlobIndex(blobNumber, blobCount, clusterNumber);
            long blobStart = offsetAt(body, blobNumber, extended);
            long blobEnd = offsetAt(body, blobNumber + 1, extended);
            return in.bytes(body + blobStart, Math.toIntExact(blobEnd - blobStart));
        }

        Decoded decoded = decodedCluster(clusterNumber, start, compression, extended);
        long blobCount = decoded.offsets().length - 1;
        checkBlobIndex(blobNumber, blobCount, clusterNumber);
        int from = Math.toIntExact(decoded.offsets()[(int) blobNumber]);
        int to = Math.toIntExact(decoded.offsets()[(int) blobNumber + 1]);
        return Arrays.copyOfRange(decoded.data(), from, to);
    }

    /**
     * The blob's length without materializing it — needed for a {@code Content-Range} header.
     * Cheap for an uncompressed cluster (two offset reads); a compressed one must be decoded, but
     * that result is cached and the caller is about to read from it anyway.
     */
    long blobSize(long clusterNumber, long blobNumber) throws IOException {
        Located located = locate(clusterNumber, blobNumber);
        return located.end() - located.start();
    }

    /**
     * A slice of a blob, without ever holding the whole blob.
     *
     * <p>This is what lets a seek in a 20 MB video cost the bytes actually asked for. The
     * uncompressed path preads exactly the requested window; the compressed path copies out of the
     * already-cached decompressed cluster.
     */
    byte[] blobRange(long clusterNumber, long blobNumber, long offset, int length) throws IOException {
        Located located = locate(clusterNumber, blobNumber);
        long size = located.end() - located.start();
        if (offset < 0 || offset > size) {
            throw new ZimFormatException("Range offset " + offset + " outside blob of " + size);
        }
        int take = (int) Math.min(length, size - offset);
        if (take <= 0) {
            return new byte[0];
        }
        if (located.data() == null) {
            return in.bytes(located.base() + located.start() + offset, take);
        }
        int from = Math.toIntExact(located.start() + offset);
        return Arrays.copyOfRange(located.data(), from, from + take);
    }

    /**
     * Where a blob lives. {@code data} is null for an uncompressed cluster, in which case the
     * offsets are relative to {@code base} within the file; otherwise they index {@code data}.
     */
    private record Located(byte[] data, long base, long start, long end) {}

    private Located locate(long clusterNumber, long blobNumber) throws IOException {
        if (clusterNumber < 0 || clusterNumber >= header.clusterCount()) {
            throw new ZimFormatException("Cluster " + clusterNumber + " out of range");
        }
        long start = in.u64(header.clusterPtrPos() + 8 * clusterNumber);
        int info = in.u8(start);
        int compression = info & 0x0F;
        boolean extended = (info & 0x10) != 0;
        int offSize = extended ? 8 : 4;
        long body = start + 1;

        if (compression == COMP_DEFAULT || compression == COMP_NONE) {
            long first = offsetAt(body, 0, extended);
            checkBlobIndex(blobNumber, first / offSize - 1, clusterNumber);
            return new Located(
                    null, body, offsetAt(body, blobNumber, extended), offsetAt(body, blobNumber + 1, extended));
        }
        Decoded decoded = decodedCluster(clusterNumber, start, compression, extended);
        checkBlobIndex(blobNumber, decoded.offsets().length - 1, clusterNumber);
        return new Located(
                decoded.data(), 0, decoded.offsets()[(int) blobNumber], decoded.offsets()[(int) blobNumber + 1]);
    }

    private long offsetAt(long body, long index, boolean extended) throws IOException {
        return extended ? in.u64(body + 8 * index) : in.u32(body + 4 * index);
    }

    private static void checkBlobIndex(long blobNumber, long blobCount, long cluster) throws ZimFormatException {
        if (blobNumber < 0 || blobNumber >= blobCount) {
            throw new ZimFormatException("Blob " + blobNumber + " out of range in cluster " + cluster);
        }
    }

    private synchronized Decoded decodedCluster(long clusterNumber, long start, int compression, boolean extended)
            throws IOException {
        Decoded cached = cache.get(clusterNumber);
        if (cached != null) {
            return cached;
        }
        long end = clusterEnd(clusterNumber);
        byte[] compressed = in.bytes(start + 1, Math.toIntExact(end - start - 1));
        byte[] data = decompress(compressed, compression);

        int offSize = extended ? 8 : 4;
        long first = readOffset(data, 0, extended);
        int blobCount = Math.toIntExact(first / offSize - 1);
        long[] offsets = new long[blobCount + 1];
        for (int i = 0; i <= blobCount; i++) {
            offsets[i] = readOffset(data, i, extended);
        }
        Decoded decoded = new Decoded(offsets, data);
        admit(clusterNumber, decoded);
        return decoded;
    }

    /**
     * Caches a cluster, evicting least-recently-used entries until the total fits. A cluster
     * larger than the whole budget is returned to the caller but never cached — admitting it
     * would evict everything else to hold one item.
     */
    private void admit(long clusterNumber, Decoded decoded) {
        long size = decoded.data().length;
        if (size > maxCacheBytes) {
            return;
        }
        var eldest = cache.entrySet().iterator();
        while (cachedBytes + size > maxCacheBytes && eldest.hasNext()) {
            cachedBytes -= eldest.next().getValue().data().length;
            eldest.remove();
        }
        cache.put(clusterNumber, decoded);
        cachedBytes += size;
    }

    /** Total decompressed bytes currently retained — the quantity the bound actually cares about. */
    synchronized long cachedBytes() {
        return cachedBytes;
    }

    synchronized int cachedClusterCount() {
        return cache.size();
    }

    private long clusterEnd(long clusterNumber) throws IOException {
        if (clusterNumber + 1 < header.clusterCount()) {
            return in.u64(header.clusterPtrPos() + 8 * (clusterNumber + 1));
        }
        long checksum = header.checksumPos();
        return (checksum > 0 && checksum <= in.size()) ? checksum : in.size();
    }

    private static long readOffset(byte[] data, int index, boolean extended) {
        var buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return extended ? buf.getLong(index * 8) : (buf.getInt(index * 4) & 0xFFFFFFFFL);
    }

    private static byte[] decompress(byte[] compressed, int compression) throws IOException {
        InputStream stream =
                switch (compression) {
                    case COMP_XZ -> new XZInputStream(new ByteArrayInputStream(compressed));
                    case COMP_ZSTD -> new ZstdInputStream(new ByteArrayInputStream(compressed));
                    default -> throw new ZimFormatException("Unsupported cluster compression " + compression);
                };
        try (InputStream s = stream) {
            var out = new java.io.ByteArrayOutputStream(Math.min(compressed.length * 4, 1 << 20));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_DECOMPRESSED) {
                    throw new ZimFormatException("Cluster decompresses past " + MAX_DECOMPRESSED + " bytes");
                }
            }
            return out.toByteArray();
        }
    }
}
