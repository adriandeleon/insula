package com.insula.download;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantineTest {

    @Test
    void movesAsideAndKeepsTheBytes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wiki.zim");
        Files.writeString(file, "payload");

        Path quarantined = Quarantine.quarantine(file);

        assertFalse(Files.exists(file), "the bad file should not stay where the library looks");
        assertTrue(Files.exists(quarantined));
        assertEquals("payload", Files.readString(quarantined), "bytes must be preserved, not deleted");
        assertEquals("wiki.zim" + Quarantine.SUFFIX, quarantined.getFileName().toString());
    }

    @Test
    void dropsStaleResumeStateSoARetryStartsClean(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wiki.zim");
        Files.writeString(file, "payload");
        Path part = dir.resolve("wiki.zim" + HttpMultiSourceTransport.PART_SUFFIX);
        Files.writeString(part, "111", StandardCharsets.US_ASCII);

        Quarantine.quarantine(file);

        assertFalse(Files.exists(part), "a bitmap describing rejected bytes must not survive");
    }

    @Test
    void neverOverwritesAnEarlierQuarantine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("wiki.zim");
        Files.writeString(file, "first");
        Quarantine.quarantine(file);

        Files.writeString(file, "second");
        Path second = Quarantine.quarantine(file);

        assertEquals("wiki.zim" + Quarantine.SUFFIX + ".2", second.getFileName().toString());
        assertEquals("first", Files.readString(dir.resolve("wiki.zim" + Quarantine.SUFFIX)));
        assertEquals("second", Files.readString(second));
    }
}
