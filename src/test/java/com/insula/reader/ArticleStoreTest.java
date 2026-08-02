package com.insula.reader;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleStoreTest {

    private static ArticleRef ref(String path) {
        return new ArticleRef(Path.of("/lib/wikipedia.zim"), path, "Title of " + path, "Wikipedia");
    }

    @Test
    void aReferenceRoundTripsThroughItsEncodedForm() {
        ArticleRef original = ref("C/Walt_Disney");
        ArticleRef decoded = ArticleRef.decode(original.encode());
        assertEquals(original, decoded);
    }

    @Test
    void encodingSurvivesCharactersThatWouldBreakAPropertiesFile() {
        // Paths really do contain these: an article title with "|", a path with "=", a newline in
        // a mangled title. Any of them would corrupt the store if written raw.
        ArticleRef awkward = new ArticleRef(
                Path.of("/lib/my books/a=b.zim"), "C/Pipe|Path", "Title = with | and \n newline", "Book|Name");
        ArticleRef decoded = ArticleRef.decode(awkward.encode());
        assertEquals(awkward, decoded);
        assertFalse(awkward.encode().contains("\n"), "an encoded entry must stay on one line");
    }

    @Test
    void malformedLinesAreSkippedRatherThanThrowing() {
        assertNull(ArticleRef.decode(null));
        assertNull(ArticleRef.decode(""));
        assertNull(ArticleRef.decode("onlyonefield"));
        // Present but empty article path is not a usable reference.
        assertNull(ArticleRef.decode("%2Flib%2Fa.zim||T"));
        assertNotNull(ArticleRef.decode("%2Flib%2Fa.zim|C%2FA|T"), "a three-field line predates bookTitle");
    }

    @Test
    void aMissingTitleFallsBackToThePath() {
        assertEquals("C/A", new ArticleRef(Path.of("/a.zim"), "C/A", null, "B").title());
        assertEquals("C/A", new ArticleRef(Path.of("/a.zim"), "C/A", "  ", "B").title());
    }

    @Test
    void identityIgnoresTitleSoARenamedArticleIsNotADuplicate() {
        ArticleRef a = new ArticleRef(Path.of("/lib/w.zim"), "C/A", "Old title", "W");
        ArticleRef b = new ArticleRef(Path.of("/lib/w.zim"), "C/A", "New title", "W");
        assertEquals(a.key(), b.key());
        // ...but the same path in another archive is a different article.
        assertFalse(a.key().equals(new ArticleRef(Path.of("/lib/x.zim"), "C/A", "T", "X").key()));
    }

    @Test
    void addFirstMovesAnExistingEntryToTheFront(@TempDir Path dir) {
        ArticleStore store = ArticleStore.load(dir.resolve("s.properties"), 10);
        store.addFirst(ref("C/A"));
        store.addFirst(ref("C/B"));
        store.addFirst(ref("C/A"));

        assertEquals(2, store.size(), "revisiting must not duplicate");
        assertEquals("C/A", store.entries().getFirst().articlePath(), "most recent first");
        assertEquals("C/B", store.entries().getLast().articlePath());
    }

    @Test
    void addLastKeepsExistingPositionSoBookmarkOrderIsStable(@TempDir Path dir) {
        ArticleStore store = ArticleStore.load(dir.resolve("s.properties"), 10);
        store.addLast(ref("C/A"));
        store.addLast(ref("C/B"));
        store.addLast(ref("C/A")); // bookmarking again must not reorder the list

        assertEquals(
                List.of("C/A", "C/B"),
                store.entries().stream().map(ArticleRef::articlePath).toList());
    }

    @Test
    void theStoreIsBoundedAndDropsTheOldest(@TempDir Path dir) {
        ArticleStore store = ArticleStore.load(dir.resolve("s.properties"), 3);
        for (int i = 0; i < 6; i++) {
            store.addFirst(ref("C/" + i));
        }
        assertEquals(3, store.size());
        assertEquals(
                List.of("C/5", "C/4", "C/3"),
                store.entries().stream().map(ArticleRef::articlePath).toList(),
                "the newest three survive");
    }

    @Test
    void orderSurvivesASaveAndReload(@TempDir Path dir) {
        // Properties are unordered, so the store keys by a zero-padded index on purpose; without
        // it a reloaded history would come back shuffled.
        Path file = dir.resolve("s.properties");
        ArticleStore store = ArticleStore.load(file, 100);
        for (int i = 0; i < 12; i++) {
            store.addLast(ref("C/" + i));
        }
        store.save();

        List<String> reloaded = ArticleStore.load(file, 100).entries().stream()
                .map(ArticleRef::articlePath)
                .toList();
        assertEquals(
                List.of("C/0", "C/1", "C/2", "C/3", "C/4", "C/5", "C/6", "C/7", "C/8", "C/9", "C/10", "C/11"),
                reloaded,
                "twelve entries prove the padding: plain string keys would put C/10 before C/2");
    }

    @Test
    void removeAndClearWork(@TempDir Path dir) {
        ArticleStore store = ArticleStore.load(dir.resolve("s.properties"), 10);
        store.addLast(ref("C/A"));
        store.addLast(ref("C/B"));

        assertTrue(store.contains(ref("C/A")));
        assertTrue(store.remove(ref("C/A")));
        assertFalse(store.remove(ref("C/A")), "removing twice reports honestly");
        assertFalse(store.contains(ref("C/A")));

        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    void anAbsentOrGarbledFileLoadsAsEmpty(@TempDir Path dir) throws Exception {
        assertEquals(0, ArticleStore.load(dir.resolve("missing.properties"), 10).size());

        Path garbled = dir.resolve("garbled.properties");
        java.nio.file.Files.writeString(garbled, "000000=not-a-valid-entry\n000001=\n");
        assertEquals(0, ArticleStore.load(garbled, 10).size(), "unusable lines are skipped, not fatal");
    }
}
