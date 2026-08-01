package com.offlinewiki.command;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteFilterTest {

    private static final List<Command> COMMANDS = List.of(
            Command.of("file.open", "Open ZIM Archive…", () -> {}),
            Command.of("view.settings", "Open Settings", () -> {}),
            Command.of("nav.home", "Go to Main Page", () -> {}),
            Command.of("view.zoomIn", "Zoom In", () -> {}));

    private static List<String> ids(String query) {
        return PaletteFilter.filter(query, COMMANDS).stream().map(Command::id).toList();
    }

    @Test
    void emptyQueryKeepsRegistrationOrder() {
        assertEquals(List.of("file.open", "view.settings", "nav.home", "view.zoomIn"), ids(""));
        assertEquals(ids(""), ids("   "));
    }

    @Test
    void titlePrefixOutranksSubstring() {
        // "Open ZIM Archive…" and "Open Settings" both prefix-match; "Zoom In" does not match at all.
        assertEquals(List.of("view.settings", "file.open"), ids("open"));
    }

    @Test
    void matchesTitleSubstringAndIsCaseInsensitive() {
        assertEquals(List.of("nav.home"), ids("main"));
        assertEquals(ids("MAIN"), ids("main"));
    }

    @Test
    void fallsBackToIdMatch() {
        // No title contains "nav", but the id does.
        assertEquals(List.of("nav.home"), ids("nav."));
    }

    @Test
    void rankOrdersPrefixBeforeSubstringBeforeId() {
        List<String> hits = ids("zoom");
        assertEquals("view.zoomIn", hits.getFirst()); // title prefix "Zoom In" wins over the id match
        assertTrue(hits.contains("view.zoomIn"));
    }

    @Test
    void noMatchesYieldsEmptyList() {
        assertTrue(ids("definitely-not-a-command").isEmpty());
    }

    @Test
    void nullQueryIsTreatedAsEmpty() {
        assertFalse(PaletteFilter.filter(null, COMMANDS).isEmpty());
    }
}
