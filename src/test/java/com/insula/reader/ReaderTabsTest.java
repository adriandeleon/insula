package com.insula.reader;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderTabsTest {

    private static ArticleRef ref(String path) {
        return new ArticleRef(Path.of("/lib/w.zim"), path, path, "W");
    }

    private static List<String> labels(ReaderTabs tabs) {
        return tabs.tabs().stream().map(ReaderTabs.Tab::label).toList();
    }

    @Test
    void theFirstTabBecomesActiveEvenWhenNotAskedFor() {
        ReaderTabs tabs = new ReaderTabs();
        assertNull(tabs.active());
        tabs.open(ref("A"), false);
        assertEquals(0, tabs.activeIndex(), "there is nothing else it could be showing");
    }

    @Test
    void aNewTabOpensNextToItsSourceNotAtTheEnd() {
        // A link opened in a background tab belongs beside the article it came from; appending it
        // to the end scatters related reading across the strip.
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        tabs.open(ref("B"), true);
        tabs.select(0);
        tabs.open(ref("C"), false);

        assertEquals(List.of("A", "C", "B"), labels(tabs));
        assertEquals(0, tabs.activeIndex(), "opening in the background must not steal focus");
    }

    @Test
    void openingBeforeTheActiveTabKeepsTheSameTabShowing() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        tabs.open(ref("B"), true); // active = B at index 1
        tabs.select(1);
        ReaderTabs.Tab showing = tabs.active();
        tabs.open(ref("C"), false); // inserted at index 2, after the active one
        assertSame(showing, tabs.active(), "the visible article must not change under the reader");
    }

    @Test
    void closingTheActiveTabMovesRightThenFallsBackLeft() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        tabs.open(ref("B"), true);
        tabs.open(ref("C"), true);
        tabs.select(1); // showing B

        assertEquals("C", tabs.close(1).label(), "closing moves to the right, browser-style");
        assertEquals(List.of("A", "C"), labels(tabs));

        tabs.select(1); // showing C, the last tab
        assertEquals("A", tabs.close(1).label(), "at the end there is only left to fall back to");
    }

    @Test
    void closingATabLeftOfTheActiveOneKeepsTheSameArticleShowing() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        tabs.open(ref("B"), true);
        tabs.open(ref("C"), true);
        tabs.select(2);
        ReaderTabs.Tab showing = tabs.active();

        tabs.close(0);
        assertSame(showing, tabs.active(), "the index shifted but the article must not");
        assertEquals(1, tabs.activeIndex());
    }

    @Test
    void closingTheLastTabLeavesNothingShowing() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        assertNull(tabs.closeActive());
        assertEquals(0, tabs.count());
        assertEquals(-1, tabs.activeIndex());
        // And the empty state must not throw when driven further.
        assertNull(tabs.next());
        assertNull(tabs.previous());
        assertNull(tabs.closeActive());
    }

    @Test
    void closingOutOfRangeIsIgnored() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        assertEquals("A", tabs.close(7).label());
        assertEquals(1, tabs.count());
    }

    @Test
    void cyclingWrapsInBothDirections() {
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(ref("A"), true);
        tabs.open(ref("B"), true);
        tabs.open(ref("C"), true);
        tabs.select(2);

        assertEquals("A", tabs.next().label(), "Ctrl+Tab is a cycle, not a wall");
        assertEquals("C", tabs.previous().label());
    }

    @Test
    void scrollIsRememberedPerTabAndClamped() {
        ReaderTabs tabs = new ReaderTabs();
        ReaderTabs.Tab tab = tabs.open(ref("A"), true);
        tab.setScroll(0.42);
        assertEquals(0.42, tab.scroll(), 1e-9);
        tab.setScroll(5);
        assertEquals(1.0, tab.scroll(), 1e-9);
        tab.setScroll(-1);
        assertEquals(0.0, tab.scroll(), 1e-9);
    }

    @Test
    void selectingByIdentityFindsTheTabOrReportsItIsGone() {
        ReaderTabs tabs = new ReaderTabs();
        ReaderTabs.Tab a = tabs.open(ref("A"), true);
        tabs.open(ref("B"), true);
        assertTrue(tabs.selectTab(a));
        assertSame(a, tabs.active());

        tabs.close(0);
        assertFalse(tabs.selectTab(a), "a closed tab cannot be selected");
    }

    @Test
    void archiveChipsAppearOnlyOnceTheTabsSpanMoreThanOneArchive() {
        // A chip on every tab of a single-archive session repeats the same word down the strip.
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(new ArticleRef(java.nio.file.Path.of("/a.zim"), "C/One", "One", "Wikipedia"), true);
        tabs.open(new ArticleRef(java.nio.file.Path.of("/a.zim"), "C/Two", "Two", "Wikipedia"), true);
        assertFalse(tabs.showsArchiveChips());
        assertEquals("", tabs.chipFor(tabs.active()));

        tabs.open(new ArticleRef(java.nio.file.Path.of("/b.zim"), "C/Three", "Three", "TED"), true);
        assertTrue(tabs.showsArchiveChips());
        assertEquals("TED", tabs.chipFor(tabs.active()));
        assertEquals("Wikipedia", tabs.chipFor(tabs.tabs().getFirst()));
    }

    @Test
    void twoArchivesSharingATitleStillGetChips() {
        // The maxi and mini builds of one project both call themselves "Wikipedia"; keying the
        // decision on the title would leave exactly those tabs unlabelled.
        ReaderTabs tabs = new ReaderTabs();
        tabs.open(new ArticleRef(Path.of("/maxi.zim"), "C/One", "One", "Wikipedia"), true);
        tabs.open(new ArticleRef(Path.of("/mini.zim"), "C/Two", "Two", "Wikipedia"), true);
        assertTrue(tabs.showsArchiveChips());
        assertEquals("Wikipedia", tabs.chipFor(tabs.active()));
    }
}
