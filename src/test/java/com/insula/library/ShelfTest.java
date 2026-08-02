package com.insula.library;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the shelf arranges itself: pinning, grouping, sorting. */
class ShelfTest {

    private static LibraryEntry entry(String fileName, String title, long size, long added) {
        return new LibraryEntry(Path.of("/lib/" + fileName), title, size, "", true, added);
    }

    private static List<String> titles(List<LibraryEntry> entries) {
        return entries.stream().map(LibraryEntry::title).toList();
    }

    @Test
    void pinnedArchivesSitAboveEveryGrouping() {
        // The star is the whole favourites model, so it outranks the grouping rather than
        // sorting within one.
        List<LibraryEntry> entries = List.of(
                entry("gutenberg_en_all.zim", "Gutenberg", 10, 1),
                entry("wikipedia_en_all.zim", "Wikipedia", 20, 2).withPinned(true));

        List<Shelf.Group> groups = Shelf.arrange(entries, Shelf.GroupBy.THEME, Shelf.SortBy.NAME);
        assertEquals(Shelf.PINNED, groups.getFirst().title());
        assertEquals(List.of("Wikipedia"), titles(groups.getFirst().entries()));
        assertTrue(groups.stream().skip(1).noneMatch(g -> titles(g.entries()).contains("Wikipedia")));
    }

    @Test
    void emptySectionsAreAbsentRatherThanEmpty() {
        // Most days the Library is just the shelf; a "Pinned" heading over nothing is noise.
        List<Shelf.Group> groups = Shelf.arrange(
                List.of(entry("wikipedia_en_all.zim", "W", 1, 1)), Shelf.GroupBy.THEME, Shelf.SortBy.NAME);
        assertTrue(groups.stream().noneMatch(g -> g.title().equals(Shelf.PINNED)));
        assertEquals(1, groups.size());
        assertEquals(List.of(), Shelf.arrange(List.of(), Shelf.GroupBy.THEME, Shelf.SortBy.NAME));
    }

    @Test
    void themesComeFromTheArchiveNameWithoutAnyTagging() {
        assertEquals("Encyclopedias & reference", Shelf.themeOf("wikipedia_en_all_mini_2026-07.zim"));
        assertEquals("Encyclopedias & reference", Shelf.themeOf("wiktionary_de_all.zim"));
        assertEquals("Books", Shelf.themeOf("gutenberg_en_all.zim"));
        assertEquals("Courses & talks", Shelf.themeOf("ted_mul_tech.zim"));
        assertEquals("Q&A", Shelf.themeOf("askubuntu_en_all.zim"));
        // An unknown project still gets a readable heading rather than falling in "Other".
        assertEquals("Zimgit", Shelf.themeOf("zimgit_post-disaster_en.zim"));
    }

    @Test
    void aPerArchiveThemeOverridesTheDerivedOne() {
        LibraryEntry moved = entry("wikipedia_en_all.zim", "W", 1, 1).withTheme("Bedtime reading");
        List<Shelf.Group> groups = Shelf.arrange(List.of(moved), Shelf.GroupBy.THEME, Shelf.SortBy.NAME);
        assertEquals("Bedtime reading", groups.getFirst().title());
    }

    @Test
    void theCatchAllGroupSortsLastWhateverItIsCalled() {
        List<LibraryEntry> entries = List.of(
                entry("noproject.zim", "Loose file", 1, 1).withTheme(""),
                entry("wikipedia_en_all.zim", "Wikipedia", 1, 1));
        List<Shelf.Group> groups = Shelf.arrange(entries, Shelf.GroupBy.LANGUAGE, Shelf.SortBy.NAME);
        assertEquals(Shelf.UNGROUPED, groups.getLast().title(), "the leftovers never head the shelf");
    }

    @Test
    void groupingByLanguageAndPublisherReadsTheNameToo() {
        assertEquals("en", Shelf.languageOf("wikipedia_en_all_mini.zim"));
        assertEquals("mul", Shelf.languageOf("ted_mul_tech.zim"));
        assertEquals("", Shelf.languageOf("noproject.zim"));
        assertEquals("TED", Shelf.publisherOf("ted_mul_tech.zim"), "a short project reads as an acronym");
        assertEquals("Wikipedia", Shelf.publisherOf("wikipedia_en_all.zim"));
    }

    @Test
    void everySortOrdersAsItsNameClaims() {
        List<LibraryEntry> entries = List.of(
                entry("b_en_all_2025-01.zim", "Beta", 300, 100),
                entry("a_en_all_2026-08.zim", "Alpha", 100, 300),
                entry("c_en_all_2026-02.zim", "Gamma", 200, 200));

        assertEquals(
                List.of("Alpha", "Beta", "Gamma"),
                titles(entries.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.NAME))
                        .toList()));
        assertEquals(
                List.of("Beta", "Gamma", "Alpha"),
                titles(entries.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.SIZE))
                        .toList()),
                "largest first");
        assertEquals(
                List.of("Alpha", "Gamma", "Beta"),
                titles(entries.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.RECENT))
                        .toList()),
                "most recently added first");
        assertEquals(
                List.of("Alpha", "Gamma", "Beta"),
                titles(entries.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.BUILD_DATE))
                        .toList()),
                "newest build first");
    }

    @Test
    void customSortIsStableOnAFreshLibraryWhereEveryOrderIsZero() {
        // Nothing has been dragged yet, so every order is 0; falling back to name keeps the shelf
        // from shuffling on each launch.
        List<LibraryEntry> entries =
                List.of(entry("c.zim", "Gamma", 1, 1), entry("a.zim", "Alpha", 1, 1), entry("b.zim", "Beta", 1, 1));
        assertEquals(
                List.of("Alpha", "Beta", "Gamma"),
                titles(entries.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.CUSTOM))
                        .toList()));
    }

    @Test
    void reorderRenumbersDenselySoALaterDragCannotCollide() {
        List<LibraryEntry> dragged = List.of(
                entry("c.zim", "Gamma", 1, 1).withOrder(9),
                entry("a.zim", "Alpha", 1, 1).withOrder(9),
                entry("b.zim", "Beta", 1, 1).withOrder(3));
        List<LibraryEntry> renumbered = Shelf.reorder(dragged);
        assertEquals(
                List.of(0, 1, 2), renumbered.stream().map(LibraryEntry::order).toList());
        assertEquals(
                List.of("Gamma", "Alpha", "Beta"),
                titles(renumbered.stream()
                        .sorted(Shelf.comparator(Shelf.SortBy.CUSTOM))
                        .toList()),
                "the dragged arrangement is what sorting Custom reproduces");
    }

    @Test
    void switchingSortDoesNotDiscardTheCustomArrangement() {
        // The kit is explicit: any other sort greys the drag handles rather than silently
        // throwing the arrangement away.
        List<LibraryEntry> arranged = Shelf.reorder(
                List.of(entry("c.zim", "Gamma", 1, 1), entry("a.zim", "Alpha", 1, 1), entry("b.zim", "Beta", 1, 1)));
        List<String> byName = titles(Shelf.arrange(arranged, Shelf.GroupBy.NONE, Shelf.SortBy.NAME)
                .getFirst()
                .entries());
        assertEquals(List.of("Alpha", "Beta", "Gamma"), byName);

        List<String> backToCustom = titles(Shelf.arrange(arranged, Shelf.GroupBy.NONE, Shelf.SortBy.CUSTOM)
                .getFirst()
                .entries());
        assertEquals(List.of("Gamma", "Alpha", "Beta"), backToCustom, "the arrangement survived the detour");
    }
}
