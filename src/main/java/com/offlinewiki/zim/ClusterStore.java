package com.offlinewiki.zim;

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

    private static final int CACHE_SIZE = 32;
    /** Safety cap on a single decompressed cluster (nominal cluster size is ~2 MB). */
    private static final int MAX_DECOMPRESSED = 128 * 1024 * 1024;

    private record Decoded(long[] offsets, byte[] data) {}

    private final LittleEndianFile in;
    private final ZimHeader header;

    private final Map<Long, Decoded> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Decoded> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    ClusterStore(LittleEndianFile in, ZimHeader header) {
        this.in = in;
        this.header = header;
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
        cache.put(clusterNumber, decoded);
        return decoded;
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
