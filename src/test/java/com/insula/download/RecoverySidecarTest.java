package com.insula.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.insula.catalog.ZimEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoverySidecarTest {

    private static ZimEntry entry() {
        return new ZimEntry(
                "id-1",
                "Wikipedia",
                "",
                "wikipedia_en_all",
                "mini",
                List.of("eng"),
                "wikipedia",
                100,
                0,
                "https://example.org/wikipedia_en_all_mini_2026-06.zim.meta4",
                1000);
    }

    @Test
    void roundTripsTitleAndMetalinkUrl(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("wikipedia_en_all_mini_2026-06.zim");
        RecoverySidecar.write(zim, entry());
        RecoverySidecar.Info info = RecoverySidecar.read(zim).orElseThrow();
        assertEquals("Wikipedia", info.title());
        assertEquals("https://example.org/wikipedia_en_all_mini_2026-06.zim.meta4", info.metalinkUrl());

        RecoverySidecar.delete(zim);
        assertTrue(RecoverySidecar.read(zim).isEmpty());
        assertFalse(Files.exists(RecoverySidecar.sidecarFor(zim)));
    }

    @Test
    void quarantinedNamesMapBackToTheirZim(@TempDir Path dir) {
        Path zim = dir.resolve("x.zim");
        assertEquals(zim, RecoverySidecar.zimFor(dir.resolve("x.zim.corrupt")));
        assertEquals(zim, RecoverySidecar.zimFor(dir.resolve("x.zim.corrupt.2")));
        assertEquals(zim, RecoverySidecar.zimFor(zim), "a non-quarantined name maps to itself");
    }

    @Test
    void missingOrUrlLessSidecarReadsAsEmpty(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("x.zim");
        assertTrue(RecoverySidecar.read(zim).isEmpty());
        Files.writeString(RecoverySidecar.sidecarFor(zim), "title=X\n");
        assertTrue(RecoverySidecar.read(zim).isEmpty(), "a sidecar without a metalink URL is useless");
    }
}
