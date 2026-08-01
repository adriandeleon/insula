package com.offlinewiki.download;

import java.util.List;
import java.util.Optional;

/**
 * A parsed MirrorBrain {@code .meta4} sidecar.
 *
 * <p>Beyond the mirror list this carries <b>piece hashes</b> — SHA-1 over fixed-size blocks
 * (4 MiB in the live catalog) — so an HTTP transport can verify each chunk as it lands instead of
 * discovering corruption only at the end. That is the property usually attributed to BitTorrent.
 *
 * @param mirrors mirror URLs, best (lowest {@code priority}) first
 * @param pieceLength bytes per piece, or 0 when the file has no piece hashes
 * @param pieceHashes lowercase hex SHA-1 per piece, in file order
 */
public record Metalink(
        String fileName,
        long size,
        String sha256,
        String md5,
        List<String> mirrors,
        long pieceLength,
        String pieceHashType,
        List<String> pieceHashes) {

    public Metalink {
        mirrors = mirrors == null ? List.of() : List.copyOf(mirrors);
        pieceHashes = pieceHashes == null ? List.of() : List.copyOf(pieceHashes);
    }

    public Optional<String> sha256Hash() {
        return sha256 == null || sha256.isBlank() ? Optional.empty() : Optional.of(sha256);
    }

    /** Whether per-chunk verification is possible for this file. */
    public boolean hasPieceHashes() {
        return pieceLength > 0 && !pieceHashes.isEmpty() && "sha-1".equalsIgnoreCase(pieceHashType);
    }

    /** Byte range [start, end) of piece {@code index}; the last piece is short. */
    public long[] pieceRange(int index) {
        if (index < 0 || index >= pieceHashes.size()) {
            throw new IndexOutOfBoundsException("piece " + index + " of " + pieceHashes.size());
        }
        long start = (long) index * pieceLength;
        return new long[] {start, Math.min(start + pieceLength, size)};
    }
}
