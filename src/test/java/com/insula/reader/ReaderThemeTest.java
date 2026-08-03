package com.insula.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderThemeTest {

    @Test
    void originalModeAtDefaultsInjectsNothing() {
        // Costs nothing and leaves the archive exactly as its author styled it.
        assertEquals("", ReaderTheme.css(ReaderTheme.Mode.ORIGINAL, ReaderTheme.MAX_WIDTH, 1.0));
    }

    @Test
    void darkModeOverridesTheArchivesOwnColours() {
        String css = ReaderTheme.css(ReaderTheme.Mode.DARK, ReaderTheme.MAX_WIDTH, 1.0);
        assertTrue(css.contains("!important"), "overriding a stylesheet we do not control needs !important");
        assertTrue(css.contains("background"), css);
        // Tables are where naive dark modes fail: archived pages shade them explicitly.
        assertTrue(css.contains("th"), "table headers must be overridden");
        assertTrue(css.contains("tr:nth-child(even)"), "striped rows must be overridden");
    }

    @Test
    void theBlanketRuleCarriesZeroSpecificitySoTargetedRulesStillWin() {
        // Regression, found by reading back getComputedStyle on a real article: written as
        // `*:not(img):not(video):not(canvas):not(svg):not(svg *)` the blanket rule scores (0,0,5)
        // — higher than a bare `th` — so it silently defeated its own table-header shading and
        // every table rendered flat. Wrapping the whole selector in :where() zeroes it.
        String css = ReaderTheme.css(ReaderTheme.Mode.DARK, ReaderTheme.MAX_WIDTH, 1.0);
        assertTrue(css.contains(":where(*:not(img)"), "the blanket background rule must be zero-specificity");
        assertTrue(css.contains(":where(body *:not(a)"), "the blanket colour rule must be zero-specificity");
        assertFalse(
                css.contains("\n*:not(img)"), "a bare wildcard :not() chain out-specifies the element rules below it");
    }

    @Test
    void htmlAndBodyKeepTheDarkBackground() {
        // They must be excluded from the transparent-everything rule, or the page has no colour
        // of its own and shows through to whatever is behind it.
        String css = ReaderTheme.css(ReaderTheme.Mode.DARK, ReaderTheme.MAX_WIDTH, 1.0);
        assertTrue(css.contains(":not(html):not(body)"), css);
        assertTrue(css.contains("body { background-color:"), css);
    }

    @Test
    void darkModeDoesNotInvertImages() {
        // An inverted photograph or map is the classic giveaway of a bad dark mode.
        String css = ReaderTheme.css(ReaderTheme.Mode.DARK, ReaderTheme.MAX_WIDTH, 1.0);
        assertFalse(css.contains("invert("), css);
        assertTrue(css.contains("img") && css.contains("brightness("), "images are dimmed, not inverted");
    }

    @Test
    void widthIsAppliedOnlyWhenConstrained() {
        assertFalse(ReaderTheme.css(ReaderTheme.Mode.COMFORTABLE, ReaderTheme.MAX_WIDTH, 1.0)
                .contains("max-width"));
        assertTrue(ReaderTheme.css(ReaderTheme.Mode.COMFORTABLE, 800, 1.0).contains("max-width: 800px"));
    }

    @Test
    void widthIsClamped() {
        assertEquals(ReaderTheme.MIN_WIDTH, ReaderTheme.clampWidth(10));
        assertEquals(ReaderTheme.MAX_WIDTH, ReaderTheme.clampWidth(99999));
        assertTrue(ReaderTheme.css(ReaderTheme.Mode.COMFORTABLE, 10, 1.0).contains(ReaderTheme.MIN_WIDTH + "px"));
    }

    @Test
    void fontScaleIsRenderedAsACleanPercentage() {
        assertEquals("100", ReaderTheme.percent(1.0));
        assertEquals("125", ReaderTheme.percent(1.25));
        assertEquals("112.5", ReaderTheme.percent(1.125));
        assertTrue(ReaderTheme.css(ReaderTheme.Mode.COMFORTABLE, ReaderTheme.MAX_WIDTH, 1.25)
                .contains("font-size: 125%"));
    }

    @Test
    void modeNamesRoundTripAndUnknownFallsBackToOriginal() {
        for (ReaderTheme.Mode mode : ReaderTheme.Mode.values()) {
            assertEquals(mode, ReaderTheme.modeOf(ReaderTheme.nameOf(mode)));
        }
        assertEquals(ReaderTheme.Mode.ORIGINAL, ReaderTheme.modeOf("chartreuse"));
        assertEquals(ReaderTheme.Mode.ORIGINAL, ReaderTheme.modeOf(null));
        assertEquals(ReaderTheme.Mode.DARK, ReaderTheme.modeOf("  DARK  "));
    }

    @Test
    void aChosenTypefaceIsImposedOnTheProse() {
        String css = ReaderTheme.css(ReaderTheme.Mode.ORIGINAL, ReaderTheme.MAX_WIDTH, 1.0, "Georgia, serif");
        assertTrue(css.contains("font-family: Georgia, serif !important"), css);
        assertTrue(css.contains("body"), css);
    }

    @Test
    void theTypefaceIsNotImposedOnEverything() {
        // pre/code and the icon webfonts an archive uses for arrows and glyphs are typefaces
        // chosen for a reason: overriding those turns code into prose and icons into empty boxes.
        String css = ReaderTheme.css(ReaderTheme.Mode.ORIGINAL, ReaderTheme.MAX_WIDTH, 1.0, "Georgia");
        assertFalse(css.contains("* {"), css);
        assertFalse(css.contains("pre"), css);
        assertFalse(css.contains("code"), css);
    }

    @Test
    void leavingEverythingAsPublishedStillCostsNoStylesheet() {
        assertEquals("", ReaderTheme.css(ReaderTheme.Mode.ORIGINAL, ReaderTheme.MAX_WIDTH, 1.0, ""));
        assertEquals("", ReaderTheme.css(ReaderTheme.Mode.ORIGINAL, ReaderTheme.MAX_WIDTH, 1.0, "   "));
    }
}
