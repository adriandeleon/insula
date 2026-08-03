package com.insula.fulltext;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Building and searching an index of article text. */
class FullTextIndexTest {

    private static FullTextIndex indexOf(Path dir, String... triples) throws Exception {
        try (FullTextIndex.Builder b = FullTextIndex.builder(dir)) {
            for (int i = 0; i < triples.length; i += 3) {
                b.add(triples[i], triples[i + 1], triples[i + 2]);
            }
        }
        return FullTextIndex.open(dir);
    }

    private static List<String> paths(List<FullTextIndex.Hit> hits) {
        return hits.stream().map(FullTextIndex.Hit::path).toList();
    }

    @Test
    void aWordInTheBodyFindsTheArticle(@TempDir Path dir) throws Exception {
        try (FullTextIndex index = indexOf(
                dir,
                "A/Ray_Charles",
                "Ray Charles",
                "an American singer and composer of soul music",
                "A/Piano",
                "Piano",
                "a keyboard instrument")) {
            assertEquals(List.of("A/Ray_Charles"), paths(index.search("soul", 10)));
            assertEquals(List.of("A/Piano"), paths(index.search("keyboard", 10)));
        }
    }

    @Test
    void aTitleMatchOutranksAPassingMention(@TempDir Path dir) throws Exception {
        // Two words usually mean the article about them, not one that happens to say them.
        try (FullTextIndex index = indexOf(
                dir,
                "A/Mentions",
                "Jazz history",
                "Ray Charles is mentioned here among many others",
                "A/Ray_Charles",
                "Ray Charles",
                "a musician")) {
            assertEquals("A/Ray_Charles", paths(index.search("Ray Charles", 10)).getFirst());
        }
    }

    @Test
    void searchIsCaseInsensitive(@TempDir Path dir) throws Exception {
        try (FullTextIndex index = indexOf(dir, "A/X", "Title", "Hepatocellular Carcinoma")) {
            assertFalse(index.search("hepatocellular", 10).isEmpty());
            assertFalse(index.search("CARCINOMA", 10).isEmpty());
        }
    }

    @Test
    void aQueryIsWordsRatherThanSyntax(@TempDir Path dir) throws Exception {
        // Every piece of query syntax is a way for ordinary text to behave surprisingly. The one
        // that stings is the dangling operator: it parses perfectly and matches nothing, which
        // looks exactly like "there is nothing there".
        try (FullTextIndex index = indexOf(dir, "A/Cpp", "C++", "the C++ programming language")) {
            assertFalse(index.search("C++ (programming", 10).isEmpty(), "an unbalanced bracket");
            assertFalse(index.search("programming AND", 10).isEmpty(), "a half-typed operator");
            assertFalse(index.search("programming/language", 10).isEmpty(), "a slash is not a regex");
            assertTrue(index.search("]][[", 10).isEmpty(), "punctuation alone is not a word");
        }
    }

    @Test
    void nothingMatchingGivesNothingRatherThanEverything(@TempDir Path dir) throws Exception {
        try (FullTextIndex index = indexOf(dir, "A/X", "Title", "some words")) {
            assertTrue(index.search("zebra", 10).isEmpty());
        }
    }

    @Test
    void anEmptyQueryIsNotASearch(@TempDir Path dir) throws Exception {
        try (FullTextIndex index = indexOf(dir, "A/X", "Title", "some words")) {
            assertTrue(index.search("", 10).isEmpty());
            assertTrue(index.search("   ", 10).isEmpty());
            assertTrue(index.search(null, 10).isEmpty());
            assertTrue(index.search("words", 0).isEmpty());
        }
    }

    @Test
    void theLimitIsRespected(@TempDir Path dir) throws Exception {
        String[] many = new String[30];
        for (int i = 0; i < 10; i++) {
            many[i * 3] = "A/" + i;
            many[i * 3 + 1] = "Article " + i;
            many[i * 3 + 2] = "common word here";
        }
        try (FullTextIndex index = indexOf(dir, many)) {
            assertEquals(10, index.size());
            assertEquals(3, index.search("common", 3).size());
        }
    }

    @Test
    void rebuildingReplacesRatherThanAppends(@TempDir Path dir) throws Exception {
        // An index half-built by a cancelled run is worse than none: it answers, incompletely.
        try (FullTextIndex first = indexOf(dir, "A/Old", "Old", "gone")) {
            assertEquals(1, first.size());
        }
        try (FullTextIndex second = indexOf(dir, "A/New", "New", "here")) {
            assertEquals(1, second.size(), "the previous build is replaced");
            assertTrue(second.search("gone", 10).isEmpty());
        }
    }
}
