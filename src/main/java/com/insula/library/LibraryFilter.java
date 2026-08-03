package com.insula.library;

import java.util.List;
import java.util.Locale;

/**
 * Narrowing the shelf to what someone typed.
 *
 * <p>Matches the title, the file name and the theme, because those are the three things a reader
 * might have in mind: what it is called, what the file is called, and what shelf it sits on. A
 * blank query is not a filter — it is the absence of one, and returns everything.
 *
 * <p>Every term must match <em>something</em>, but not necessarily the same field: "wikipedia
 * medical" should find the medical Wikipedia whether "medical" is in the title or the file name.
 * Requiring one field to hold the whole query would make the obvious two-word search fail.
 *
 * <p>Pure, so the matching rules can be pinned without a shelf to render them into.
 */
public final class LibraryFilter {

    private LibraryFilter() {}

    public static boolean matches(LibraryEntry entry, String query) {
        if (entry == null) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = (entry.title() + " " + entry.fileName() + " " + entry.theme()).toLowerCase(Locale.ROOT);
        for (String term : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /** The entries that match, in the order given. */
    public static List<LibraryEntry> filter(List<LibraryEntry> entries, String query) {
        if (entries == null) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return entries;
        }
        return entries.stream().filter(e -> matches(e, query)).toList();
    }
}
