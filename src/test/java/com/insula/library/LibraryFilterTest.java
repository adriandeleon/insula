package com.insula.library;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the shelf filter matches on. */
class LibraryFilterTest {

    private static LibraryEntry entry(String file, String title, String theme) {
        return new LibraryEntry(Path.of("/a/" + file), title, 1, "", true, 0, false, 0, theme);
    }

    private static final LibraryEntry MDWIKI =
            entry("mdwiki_en_all_2025-11.zim", "MDWiki Medical Encyclopedia", "Medicine");
    private static final LibraryEntry TED = entry("ted_mul_tech_2025-10.zim", "TED Talks", "Videos");

    @Test
    void aBlankQueryIsTheAbsenceOfAFilter() {
        List<LibraryEntry> all = List.of(MDWIKI, TED);
        assertSame(all, LibraryFilter.filter(all, ""));
        assertSame(all, LibraryFilter.filter(all, "   "));
        assertSame(all, LibraryFilter.filter(all, null));
    }

    @Test
    void theTitleTheFileNameAndTheThemeAreAllSearchable() {
        assertTrue(LibraryFilter.matches(MDWIKI, "encyclopedia"), "title");
        assertTrue(LibraryFilter.matches(MDWIKI, "2025-11"), "file name");
        assertTrue(LibraryFilter.matches(MDWIKI, "medicine"), "theme");
    }

    @Test
    void caseDoesNotMatter() {
        assertTrue(LibraryFilter.matches(MDWIKI, "MDWIKI"));
        assertTrue(LibraryFilter.matches(MDWIKI, "mdwiki"));
    }

    @Test
    void termsMayMatchDifferentFields() {
        // The obvious two-word search: one word from the title, one from the file name. Requiring
        // a single field to hold the whole query would fail it.
        assertTrue(LibraryFilter.matches(MDWIKI, "medical 2025"));
        assertFalse(LibraryFilter.matches(MDWIKI, "medical 2024"), "every term still has to match");
    }

    @Test
    void nothingMatchingGivesNothing() {
        assertEquals(List.of(), LibraryFilter.filter(List.of(MDWIKI, TED), "wikipedia"));
    }

    @Test
    void theGivenOrderIsKept() {
        // The shelf's order is the user's — pinned first, or a custom arrangement. Filtering must
        // narrow it, not re-sort it.
        assertEquals(List.of(TED, MDWIKI), LibraryFilter.filter(List.of(TED, MDWIKI), "zim"));
    }
}
