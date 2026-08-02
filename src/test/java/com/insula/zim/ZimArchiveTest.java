package com.insula.zim;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Format-core tests against fixtures from openzim/zim-testing-suite. Between them the four
 * fixtures cover: old (A/I/M/-) and new (C/M/W/X) namespace schemes, and XZ (withns),
 * Zstandard (nons) and uncompressed cluster compression.
 */
class ZimArchiveTest {

    static Path fixture(String name) {
        try {
            return Path.of(ZimArchiveTest.class.getResource("/zim/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void oldNamespaceHeaderParses() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("withns-small.zim"))) {
            ZimHeader h = a.header();
            assertEquals(5, h.majorVersion());
            assertEquals(0, h.minorVersion());
            assertFalse(h.newNamespaceScheme());
            assertEquals(17, h.entryCount());
            assertEquals(2, h.clusterCount());
            assertEquals('A', a.contentNamespace());
        }
    }

    @Test
    void newNamespaceHeaderParses() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("nons-small.zim"))) {
            ZimHeader h = a.header();
            assertEquals(6, h.majorVersion());
            assertEquals(1, h.minorVersion());
            assertTrue(h.newNamespaceScheme());
            assertEquals(16, h.entryCount());
            assertEquals('C', a.contentNamespace());
        }
    }

    @Test
    void mainPageResolvesInBothSchemes() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("withns-small.zim"))) {
            Dirent main = a.mainPage().orElseThrow();
            assertEquals("A/main.html", main.fullPath());
            assertEquals("text/html", a.mimeType(main));
            String html = new String(a.content(main), StandardCharsets.UTF_8);
            assertTrue(html.contains("Test ZIM file"));
        }
        try (ZimArchive a = ZimArchive.open(fixture("nons-small.zim"))) {
            Dirent main = a.mainPage().orElseThrow();
            assertEquals("C/main.html", main.fullPath());
            assertTrue(new String(a.content(main), StandardCharsets.UTF_8).contains("Test ZIM file"));
        }
    }

    @Test
    void everyContentBlobDecompresses() throws IOException {
        // Exact totals pin the whole pipeline: dirent walk + XZ/zstd/uncompressed clusters + blob slicing.
        assertEquals(77211, totalContentBytes("withns-small.zim")); // XZ + none
        assertEquals(39530, totalContentBytes("nons-small.zim")); // zstd + none
        assertEquals(732532, totalContentBytes("withns-wikibooks.zim"));
        assertEquals(783517, totalContentBytes("nons-wikibooks.zim"));
    }

    private static long totalContentBytes(String name) throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture(name))) {
            long total = 0;
            for (long i = 0; i < a.entryCount(); i++) {
                Dirent d = a.direntAt(i);
                if (!d.isRedirect() && d.hasContent()) {
                    total += a.content(d).length;
                }
            }
            return total;
        }
    }

    @Test
    void redirectsResolveToContent() throws IOException {
        for (String name : List.of("withns-wikibooks.zim", "nons-wikibooks.zim")) {
            try (ZimArchive a = ZimArchive.open(fixture(name))) {
                int redirects = 0;
                for (long i = 0; i < a.entryCount(); i++) {
                    Dirent d = a.direntAt(i);
                    if (d.isRedirect()) {
                        redirects++;
                        assertFalse(a.resolve(d).isRedirect(), "unresolved redirect in " + name);
                    }
                }
                assertTrue(redirects > 0, name + " should contain redirects");
            }
        }
    }

    @Test
    void pathLookupHitsAndMisses() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("withns-small.zim"))) {
            assertTrue(a.entryByUrl("A/main.html").isPresent());
            assertTrue(a.entryByPath('A', "main.html").isPresent());
            assertTrue(a.entryByPath('A', "nope.html").isEmpty());
            assertTrue(a.entryByUrl("garbage").isEmpty());
        }
    }

    @Test
    void metadataReads() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("nons-wikibooks.zim"))) {
            assertEquals("Wikibooks", a.metadata("Title").orElseThrow());
            assertEquals("bel", a.metadata("Language").orElseThrow());
            assertTrue(a.metadata("NoSuchKey").isEmpty());
        }
    }

    @Test
    void titleSearchMatchesPrefixAndCapitalizedVariant() throws IOException {
        try (ZimArchive a = ZimArchive.open(fixture("withns-small.zim"))) {
            assertEquals("Test ZIM file", a.searchByTitle("Test", 10).getFirst().title());
            // lowercase query still finds the capitalized title via the variant
            assertEquals("Test ZIM file", a.searchByTitle("test", 10).getFirst().title());
            assertTrue(a.searchByTitle("zzz", 10).isEmpty());
        }
    }

    @Test
    void titleSearchWorksOnBothIndexKinds() throws IOException {
        // withns → header title pointer list; nons → X/listing/titleOrdered/v1 front-article listing
        for (String name : List.of("withns-wikibooks.zim", "nons-wikibooks.zim")) {
            try (ZimArchive a = ZimArchive.open(fixture(name))) {
                List<ZimArchive.SearchResult> hits = a.searchByTitle("А", 10);
                assertFalse(hits.isEmpty(), name);
                assertTrue(hits.getFirst().title().startsWith("А"), name + ": " + hits);
                // dedupe: no two results resolve to the same path
                assertEquals(
                        hits.stream()
                                .map(ZimArchive.SearchResult::fullPath)
                                .distinct()
                                .count(),
                        hits.size(),
                        name);
            }
        }
    }

    @Test
    void rejectsNonZimFile(@TempDir Path dir) throws IOException {
        Path bogus = dir.resolve("bogus.zim");
        Files.write(bogus, new byte[200]);
        assertThrows(ZimFormatException.class, () -> ZimArchive.open(bogus));
    }

    @Test
    void aRangedReadReturnsTheSameBytesAsTheWholeContent() throws Exception {
        // Range serving reads slices straight out of the archive so a seek in a 20 MB video costs
        // the window asked for, not the file. That only holds if the slices agree with the whole.
        try (ZimArchive archive = ZimArchive.open(fixture("nons-wikibooks.zim"))) {
            Dirent entry =
                    archive.resolve(archive.entryByUrl("C/s/bullet-icon.png").orElseThrow());
            byte[] whole = archive.content(entry);
            assertEquals(whole.length, archive.contentLength(entry), "the length must not require a read");

            assertArrayEquals(java.util.Arrays.copyOfRange(whole, 0, 10), archive.contentRange(entry, 0, 10), "head");
            assertArrayEquals(java.util.Arrays.copyOfRange(whole, 5, 25), archive.contentRange(entry, 5, 20), "middle");
            assertArrayEquals(
                    java.util.Arrays.copyOfRange(whole, whole.length - 8, whole.length),
                    archive.contentRange(entry, whole.length - 8, 8),
                    "tail");
            // Asking past the end clamps rather than throwing: the server derives lengths from the
            // same source, but a truncated archive must not become an exception storm.
            assertArrayEquals(
                    java.util.Arrays.copyOfRange(whole, whole.length - 4, whole.length),
                    archive.contentRange(entry, whole.length - 4, 999),
                    "clamped");
            assertEquals(0, archive.contentRange(entry, whole.length, 10).length, "at EOF");
        }
    }

    @Test
    void rangedReadsAgreeWithWholeReadsForEveryEntryInAnArchive() throws Exception {
        // Both cluster kinds matter here: uncompressed blobs are pread directly while compressed
        // ones are sliced out of the decoded cluster, and only one of those paths is exercised by
        // a single hand-picked entry.
        try (ZimArchive archive = ZimArchive.open(fixture("nons-small.zim"))) {
            int checked = 0;
            for (long i = 0; i < archive.entryCount(); i++) {
                Dirent d = archive.direntAt(i);
                if (d.isRedirect() || !d.hasContent()) {
                    continue;
                }
                byte[] whole = archive.content(d);
                if (whole.length < 4) {
                    continue;
                }
                int mid = whole.length / 2;
                assertArrayEquals(
                        java.util.Arrays.copyOfRange(whole, mid, whole.length),
                        archive.contentRange(d, mid, whole.length - mid),
                        d.fullPath());
                checked++;
            }
            assertTrue(checked > 0, "the fixture must contain readable entries");
        }
    }
}
