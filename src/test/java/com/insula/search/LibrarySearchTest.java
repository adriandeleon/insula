package com.insula.search;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-archive fuzzy search over the real ZIM fixtures. */
class LibrarySearchTest {

    private static Path fixture(String name) {
        try {
            return Path.of(LibrarySearchTest.class.getResource("/zim/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void indexesOnlyArticleNamespaceEntries() throws IOException {
        try (ZimArchive archive = ZimArchive.open(fixture("nons-wikibooks.zim"))) {
            TitleIndex index = TitleIndex.build(archive);
            assertTrue(index.size() > 0);
            assertTrue(
                    index.size() < archive.entryCount(),
                    "metadata, stylesheets and listings must not pollute article search");
        }
    }

    @Test
    void searchesAcrossEveryArchiveAtOnce() throws Exception {
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                ZimArchive small = ZimArchive.open(fixture("nons-small.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            search.add(fixture("nons-small.zim"), "Test ZIM", small);
            search.awaitIndexed(Duration.ofSeconds(30));
            assertEquals(2, search.readyCount());

            // A query that only the Belarusian archive can satisfy.
            List<LibrarySearch.Result> hits = search.searchNow("Азербайджанская", 10);
            assertFalse(hits.isEmpty());
            assertEquals("Wikibooks", hits.getFirst().archiveTitle(), "results carry their source archive");

            // ...and one only the other archive can.
            assertTrue(search.searchNow("Test ZIM", 10).stream()
                    .anyMatch(r -> r.archiveTitle().equals("Test ZIM")));
        }
    }

    @Test
    void resultsAreRankedBestFirstAcrossArchives() throws Exception {
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            search.awaitIndexed(Duration.ofSeconds(30));

            List<LibrarySearch.Result> hits = search.searchNow("кухня", 20);
            assertFalse(hits.isEmpty());
            for (int i = 1; i < hits.size(); i++) {
                assertTrue(hits.get(i - 1).score() >= hits.get(i).score(), "results must be ordered by score");
            }
        }
    }

    @Test
    void toleratesATypoAgainstRealTitles() throws Exception {
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            search.awaitIndexed(Duration.ofSeconds(30));

            // "Польская кухня" with one letter wrong still finds it.
            assertTrue(
                    search.searchNow("Польскяа", 10).stream()
                            .anyMatch(r -> r.title().toLowerCase(Locale.ROOT).startsWith("польская")),
                    "a single typo should not lose the article");
        }
    }

    @Test
    void honoursTheResultLimit() throws Exception {
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            search.awaitIndexed(Duration.ofSeconds(30));
            assertTrue(search.searchNow("а", 5).size() <= 5);
        }
    }

    @Test
    void anUnindexedArchiveSimplyContributesNothingYet() throws Exception {
        // Indexing is lazy, so the first keystroke may see fewer archives — it must not fail.
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            assertEquals(List.of(), search.searchNow("", 10), "a blank query returns nothing");
            search.searchNow("кухня", 10); // triggers indexing, may legitimately be empty
            search.awaitIndexed(Duration.ofSeconds(30));
            assertFalse(search.searchNow("кухня", 10).isEmpty(), "once indexed, results appear");
        }
    }

    @Test
    void removingAnArchiveDropsItsResults() throws Exception {
        try (ZimArchive be = ZimArchive.open(fixture("nons-wikibooks.zim"));
                LibrarySearch search = new LibrarySearch()) {
            search.add(fixture("nons-wikibooks.zim"), "Wikibooks", be);
            search.awaitIndexed(Duration.ofSeconds(30));
            assertFalse(search.searchNow("кухня", 10).isEmpty());

            search.remove(fixture("nons-wikibooks.zim"));
            assertTrue(search.searchNow("кухня", 10).isEmpty());
            assertEquals(List.of(), search.archives());
        }
    }
}
