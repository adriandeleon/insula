package com.insula.download;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.AppInfo;

/**
 * Fixes a quarantined file by re-fetching only its bad pieces.
 *
 * <p>A failed whole-file SHA-256 says "somewhere in these gigabytes one bit is wrong"; the
 * Metalink's per-piece SHA-1 list says <em>where</em>. Scanning locally costs disk reads only, and
 * re-fetching costs one HTTP range request per bad piece (4 MiB each in the live catalog) — for
 * the common single-flipped-bit case that turns a multi-hour re-download into seconds.
 *
 * <p>The final say still belongs to the whole-file SHA-256: piece hashes are SHA-1 and drive only
 * <em>which bytes to refetch</em>; the file is admitted nowhere unless the full digest passes.
 */
public final class PieceRepair {

    private static final Logger LOG = Logger.getLogger(PieceRepair.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private PieceRepair() {}

    /** A piece whose on-disk bytes do not match the Metalink hash. */
    public record BadPiece(int index, long offset, long length) {}

    public record Result(boolean ok, int badPieces, int repairedPieces, String sha256, String message) {}

    /**
     * Scans the file against the Metalink piece hashes. Pieces past EOF count as bad — a
     * truncated file is corruption like any other. Requires {@link Metalink#hasPieceHashes()}.
     */
    public static List<BadPiece> scan(Path file, Metalink metalink, Consumer<Integer> onPieceScanned)
            throws IOException {
        if (!metalink.hasPieceHashes()) {
            throw new IllegalArgumentException("metalink has no piece hashes");
        }
        List<BadPiece> bad = new ArrayList<>();
        MessageDigest sha1 = sha1();
        try (FileChannel channel = FileChannel.open(file)) {
            long fileSize = channel.size();
            byte[] buffer = new byte[(int) Math.min(metalink.pieceLength(), 1 << 22)];
            for (int i = 0; i < metalink.pieceHashes().size(); i++) {
                long[] range = metalink.pieceRange(i);
                long length = range[1] - range[0];
                String actual = range[1] <= fileSize ? hashAt(channel, range[0], length, sha1, buffer) : "";
                if (!actual.equalsIgnoreCase(metalink.pieceHashes().get(i))) {
                    bad.add(new BadPiece(i, range[0], length));
                }
                if (onPieceScanned != null) {
                    onPieceScanned.accept(i + 1);
                }
            }
        }
        return List.copyOf(bad);
    }

    /**
     * Scans and re-fetches bad pieces from the mirrors, then re-verifies the whole file.
     * The file is modified in place; on failure it is left as it was found (or partially
     * improved — never worse, since only hash-verified pieces are written).
     */
    public static Result repair(Path file, Metalink metalink, HttpClient http, Consumer<String> onPhase)
            throws IOException {
        Consumer<String> phase = onPhase == null ? p -> {} : onPhase;
        if (!metalink.hasPieceHashes()) {
            return new Result(false, 0, 0, "", "No piece hashes in the Metalink — cannot repair selectively");
        }

        // A file longer than published can only be wrong; trim so the scan sees the real shape.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            if (raf.length() > metalink.size()) {
                raf.setLength(metalink.size());
            }
        }

        phase.accept("Scanning pieces");
        List<BadPiece> bad = scan(file, metalink, null);
        int repaired = 0;
        if (!bad.isEmpty()) {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                if (raf.length() < metalink.size()) {
                    raf.setLength(metalink.size()); // reserve the tail; its pieces are in `bad`
                }
                for (BadPiece piece : bad) {
                    phase.accept("Fetching piece " + (repaired + 1) + " of " + bad.size());
                    byte[] data = fetchPiece(metalink, piece, http);
                    if (data == null) {
                        return new Result(
                                false,
                                bad.size(),
                                repaired,
                                "",
                                "No mirror could supply piece " + piece.index() + " intact");
                    }
                    raf.seek(piece.offset());
                    raf.write(data);
                    repaired++;
                }
            }
        }

        phase.accept("Verifying checksum");
        String published = metalink.sha256Hash().orElse("");
        if (published.isBlank()) {
            // Without a whole-file digest the piece pass is the best truth available.
            return new Result(
                    true,
                    bad.size(),
                    repaired,
                    "",
                    "Repaired " + repaired + " pieces (no SHA-256 in " + "Metalink; piece hashes all pass)");
        }
        Sha256Verifier.Verification verification = Sha256Verifier.verify(file, published, read -> {}, () -> false);
        return verification.ok()
                ? new Result(
                        true,
                        bad.size(),
                        repaired,
                        verification.actual(),
                        "Repaired " + repaired + " of " + metalink.pieceHashes().size() + " pieces; checksum verified")
                : new Result(
                        false,
                        bad.size(),
                        repaired,
                        "",
                        "Pieces pass but the file digest still differs — " + verification.describe());
    }

    /** One piece from the first mirror that answers the range with hash-matching bytes. */
    private static byte[] fetchPiece(Metalink metalink, BadPiece piece, HttpClient http) {
        MessageDigest sha1;
        try {
            sha1 = sha1();
        } catch (IOException e) {
            return null;
        }
        for (String mirror : metalink.mirrors()) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(mirror))
                        .header("User-Agent", AppInfo.USER_AGENT)
                        .header("Range", "bytes=" + piece.offset() + "-" + (piece.offset() + piece.length() - 1))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 206 || response.body().length != piece.length()) {
                    continue; // a mirror ignoring Range would hand us the whole file; skip it
                }
                sha1.reset();
                String actual = HexFormat.of().formatHex(sha1.digest(response.body()));
                if (actual.equalsIgnoreCase(metalink.pieceHashes().get(piece.index()))) {
                    return response.body();
                }
                LOG.info(() -> "Mirror " + mirror + " served a bad piece " + piece.index());
            } catch (IOException e) {
                LOG.log(Level.FINE, "Mirror failed for piece " + piece.index() + ": " + mirror, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String hashAt(FileChannel channel, long offset, long length, MessageDigest sha1, byte[] buffer)
            throws IOException {
        sha1.reset();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buffer);
        long remaining = length;
        long position = offset;
        while (remaining > 0) {
            bb.clear().limit((int) Math.min(buffer.length, remaining));
            int read = channel.read(bb, position);
            if (read <= 0) {
                return "";
            }
            sha1.update(buffer, 0, read);
            position += read;
            remaining -= read;
        }
        return HexFormat.of().formatHex(sha1.digest());
    }

    private static MessageDigest sha1() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }
}
