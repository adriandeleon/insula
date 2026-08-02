package com.insula.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Open-recent list: the interesting parts are all edge cases. */
class RecentListTest {

    @Test
    void theNewestGoesFirst() {
        assertEquals(List.of("b", "a"), RecentList.promote(List.of("a"), "b"));
    }

    @Test
    void reopeningSomethingMovesItRatherThanDuplicatingIt() {
        assertEquals(List.of("a", "c", "b"), RecentList.promote(List.of("c", "b", "a"), "a"));
    }

    @Test
    void theListIsCappedSoItStaysScannable() {
        List<String> list = List.of();
        for (int i = 0; i < RecentList.MAX + 5; i++) {
            list = RecentList.promote(list, "archive-" + i);
        }
        assertEquals(RecentList.MAX, list.size());
        assertEquals("archive-" + (RecentList.MAX + 4), list.getFirst(), "and the newest survived the cap");
    }

    @Test
    void aPathCarryingThePunctuationOneWouldReachForRoundTrips() {
        // Comma, semicolon and colon are all legal in a path, which is why the separator is not
        // any of them.
        List<String> paths = List.of("/mnt/a,b/x.zim", "/mnt/c;d/y.zim", "C:/Users/x/z.zim");
        assertEquals(paths, RecentList.decode(RecentList.encode(paths)));
    }

    @Test
    void aBlankOrMissingValueChangesNothing() {
        assertEquals(List.of("a"), RecentList.promote(List.of("a"), null));
        assertEquals(List.of("a"), RecentList.promote(List.of("a"), "  "));
    }

    @Test
    void aHalfWrittenSettingDecodesToWhateverSurvived() {
        assertTrue(RecentList.decode(null).isEmpty());
        assertTrue(RecentList.decode("").isEmpty());
        assertEquals(List.of("a", "b"), RecentList.decode("a\n\n\nb\n"));
    }

    @Test
    void removingDropsExactlyOneEntry() {
        assertEquals(List.of("a", "c"), RecentList.remove(List.of("a", "b", "c"), "b"));
        assertEquals(List.of("a", "b"), RecentList.remove(List.of("a", "b"), "missing"));
    }
}
