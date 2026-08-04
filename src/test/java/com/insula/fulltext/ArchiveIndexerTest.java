package com.insula.fulltext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The indexing pass, against real archives rather than a stand-in for one. */
class ArchiveIndexerTest {

    private static final Path NEW_SCHEME = Path.of("src/test/resources/zim/nons-wikibooks.zim");
    private static final Path OLD_SCHEME = Path.of("src/test/resources/zim/withns-wikibooks.zim");

    private static ArchiveIndexer.Result build(Path zim, Path indexDir) throws Exception {
        try (ZimArchive archive = ZimArchive.open(zim)) {
            return ArchiveIndexer.index(archive, indexDir, null, () -> false);
        }
    }

    @Test
    void aRealArchiveBecomesSearchableByItsText(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("idx");
        ArchiveIndexer.Result result = build(NEW_SCHEME, index);
        assertTrue(result.indexed() > 0, "the fixture has articles in it");
        assertFalse(result.cancelled());

        try (FullTextIndex open = FullTextIndex.open(index)) {
            assertEquals(result.indexed(), open.size());
            // Every article in this fixture is a Wikibooks page, so its own name is in the text.
            assertFalse(open.search("wikibooks", 20).isEmpty(), "a word from the pages themselves");
        }
    }

    @Test
    void bothNamespaceSchemesIndex(@TempDir Path dir) throws Exception {
        // The old A/I/M scheme and the new C/M/W/X one both have to work; contentNamespace is
        // what tells them apart, and hardcoding either would silently index nothing.
        assertTrue(build(NEW_SCHEME, dir.resolve("new")).indexed() > 0, "new scheme");
        assertTrue(build(OLD_SCHEME, dir.resolve("old")).indexed() > 0, "old scheme");
    }

    @Test
    void progressCountsArticlesAndReachesTheEnd(@TempDir Path dir) throws Exception {
        // The denominator is the articles this archive will contribute, not its entry count.
        // Most entries in a real ZIM are images — counting those leaves a bar that races to a
        // third and then crawls, which is a worse lie than no bar.
        AtomicLong lastScanned = new AtomicLong();
        AtomicLong reportedTotal = new AtomicLong();
        try (ZimArchive archive = ZimArchive.open(NEW_SCHEME)) {
            ArchiveIndexer.Result result = ArchiveIndexer.index(
                    archive,
                    dir.resolve("idx"),
                    (scanned, total, indexed) -> {
                        lastScanned.set(scanned);
                        reportedTotal.set(total);
                    },
                    () -> false);
            assertTrue(reportedTotal.get() > 0);
            assertTrue(reportedTotal.get() < archive.entryCount(), "articles are a subset of entries");
            assertEquals(reportedTotal.get(), lastScanned.get(), "the last one is reported, not just every 512th");
            assertEquals(result.indexed(), reportedTotal.get(), "everything counted was indexable");
        }
    }

    @Test
    void cancellingLeavesNoIndexBehind(@TempDir Path dir) throws Exception {
        // A half-built index is worse than none: it answers, and it answers incompletely, and
        // nothing downstream can tell the difference.
        Path index = dir.resolve("idx");
        try (ZimArchive archive = ZimArchive.open(NEW_SCHEME)) {
            ArchiveIndexer.Result result = ArchiveIndexer.index(archive, index, null, () -> true);
            assertTrue(result.cancelled());
        }
        assertFalse(Files.exists(index), "nothing is left claiming to be an index");
        assertFalse(Files.exists(index.resolveSibling(index.getFileName() + ".building")), "nor a staging folder");
    }

    @Test
    void anInterruptedRunKeepsThePreviousIndex(@TempDir Path dir) throws Exception {
        // Building in place would destroy a working index the moment somebody changed their mind.
        Path index = dir.resolve("idx");
        long first = build(NEW_SCHEME, index).indexed();
        try (ZimArchive archive = ZimArchive.open(NEW_SCHEME)) {
            ArchiveIndexer.index(archive, index, null, () -> true);
        }
        try (FullTextIndex open = FullTextIndex.open(index)) {
            assertEquals(first, open.size(), "the index that was there is still there");
        }
    }

    @Test
    void rebuildingReplacesTheOldOne(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("idx");
        long first = build(NEW_SCHEME, index).indexed();
        long second = build(NEW_SCHEME, index).indexed();
        assertEquals(first, second);
        try (FullTextIndex open = FullTextIndex.open(index)) {
            assertEquals(first, open.size(), "not twice the documents");
        }
    }

    @Test
    void everyIndexedHitCanBeOpened(@TempDir Path dir) throws Exception {
        // A hit whose path the archive cannot resolve is a dead result — the one thing a search
        // must never produce.
        Path index = dir.resolve("idx");
        build(NEW_SCHEME, index);
        try (ZimArchive archive = ZimArchive.open(NEW_SCHEME);
                FullTextIndex open = FullTextIndex.open(index)) {
            List<FullTextIndex.Hit> hits = open.search("wikibooks", 10);
            assertFalse(hits.isEmpty());
            for (FullTextIndex.Hit hit : hits) {
                assertTrue(archive.entryByUrl(hit.path()).isPresent(), "dead result: " + hit.path());
            }
        }
    }
}
