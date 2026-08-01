package com.offlinewiki.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * Streams a file through SHA-256 and compares it with the published digest.
 *
 * <p>Always run this, even after a transport that verified every piece: it also catches corruption
 * introduced <em>after</em> the write (bad disk, bad cable, a truncating crash). On a large archive
 * this takes minutes, so it reports progress and must never run on the FX thread.
 */
public final class Sha256Verifier {

    private static final int BUFFER_BYTES = 1 << 20;

    private Sha256Verifier() {}

    /** Outcome of a verification pass. */
    public record Verification(boolean ok, String expected, String actual, long bytesRead) {
        public String describe() {
            return ok ? "Checksum verified" : "Checksum mismatch: expected " + expected + " but got " + actual;
        }
    }

    /**
     * @param onProgress receives cumulative bytes hashed; called on the calling thread
     * @param cancelled polled between buffers so a quit does not wait out a 100 GB hash
     */
    public static Verification verify(
            Path file, String expectedHex, LongConsumer onProgress, java.util.function.BooleanSupplier cancelled)
            throws IOException {
        String expected = normalize(expectedHex);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        long read = 0;
        try (InputStream raw = Files.newInputStream(file);
                DigestInputStream in = new DigestInputStream(raw, digest)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) > 0) {
                read += n;
                if (onProgress != null) {
                    onProgress.accept(read);
                }
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return new Verification(false, expected, "", read);
                }
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        return new Verification(actual.equals(expected), expected, actual, read);
    }

    /** Accepts a bare digest or a {@code sha256sum} line ("<hex>  <filename>"). */
    public static String normalize(String published) {
        if (published == null) {
            return "";
        }
        String text = published.strip();
        int space = text.indexOf(' ');
        if (space > 0) {
            text = text.substring(0, space);
        }
        return text.toLowerCase(Locale.ROOT);
    }
}
