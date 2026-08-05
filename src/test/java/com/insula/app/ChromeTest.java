package com.insula.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zen truth table. Small, but the property that matters — a preference is <em>gated</em>, never
 * overwritten — is exactly the kind that reads as obviously true and regresses silently.
 */
class ChromeTest {

    @Test
    void everythingIsShowingWhenZenIsOff() {
        assertTrue(Chrome.topChrome(false));
        assertTrue(Chrome.tabStrip(false));
        assertTrue(Chrome.sidebar(true, false));
        assertFalse(Chrome.zenExit(false), "nothing to exit");
    }

    @Test
    void zenHidesTheWindowTalkingAboutItself() {
        assertFalse(Chrome.topChrome(true));
        assertFalse(Chrome.tabStrip(true));
        assertFalse(Chrome.sidebar(true, true));
        assertTrue(Chrome.zenExit(true), "the only clickable way out");
    }

    @Test
    void zenGatesTheSidebarPreferenceRatherThanReplacingIt() {
        // The point of the overlay shape: someone already reading without a sidebar must still be
        // without one after Zen, and someone with one must get it back.
        assertFalse(Chrome.sidebar(false, false), "a hidden sidebar stays hidden outside Zen");
        assertFalse(Chrome.sidebar(false, true));
        assertTrue(Chrome.sidebar(true, false), "and a shown one comes back");
    }
}
