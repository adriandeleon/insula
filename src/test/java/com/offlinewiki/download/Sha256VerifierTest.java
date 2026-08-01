package com.offlinewiki.download;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sha256VerifierTest {

    /** echo -n "hello" | sha256sum */
    private static final String HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    private static Path fileWith(Path dir, String text) throws IOException {
        Path file = dir.resolve("data.bin");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void acceptsAMatchingDigest(@TempDir Path dir) throws IOException {
        var result = Sha256Verifier.verify(fileWith(dir, "hello"), HELLO_SHA256, null, null);
        assertTrue(result.ok());
        assertEquals(5, result.bytesRead());
        assertEquals("Checksum verified", result.describe());
    }

    @Test
    void rejectsAMismatch(@TempDir Path dir) throws IOException {
        var result = Sha256Verifier.verify(fileWith(dir, "hello!"), HELLO_SHA256, null, null);
        assertFalse(result.ok());
        assertTrue(result.describe().contains("mismatch"));
    }

    @Test
    void acceptsTheSha256sumLineFormatAndOddCasing(@TempDir Path dir) throws IOException {
        // Kiwix publishes "<hex>  <filename>", and hex casing varies by tool.
        String published = HELLO_SHA256.toUpperCase(java.util.Locale.ROOT) + "  wikipedia_en_100_mini.zim";
        assertTrue(Sha256Verifier.verify(fileWith(dir, "hello"), published, null, null)
                .ok());
        assertEquals(HELLO_SHA256, Sha256Verifier.normalize(published));
        assertEquals("", Sha256Verifier.normalize(null));
    }

    @Test
    void reportsProgressMonotonicallyUpToTheFileSize(@TempDir Path dir) throws IOException {
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[5 * 1024 * 1024]);

        List<Long> progress = new ArrayList<>();
        var result = Sha256Verifier.verify(big, "00", progress::add, null);

        assertFalse(progress.isEmpty(), "a multi-minute hash must report progress");
        assertEquals(5L * 1024 * 1024, progress.getLast());
        assertEquals(result.bytesRead(), progress.getLast());
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i) > progress.get(i - 1), "progress must not go backwards");
        }
    }

    @Test
    void stopsEarlyWhenCancelled(@TempDir Path dir) throws IOException {
        Path big = dir.resolve("big.bin");
        Files.write(big, new byte[8 * 1024 * 1024]);

        AtomicBoolean cancelled = new AtomicBoolean();
        var result = Sha256Verifier.verify(big, HELLO_SHA256, read -> cancelled.set(true), cancelled::get);

        assertFalse(result.ok());
        assertTrue(result.bytesRead() < 8L * 1024 * 1024, "should not hash the whole file after cancel");
    }
}
