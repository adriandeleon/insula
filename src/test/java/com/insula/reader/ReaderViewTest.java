package com.insula.reader;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of Reader View: preference clamps, reading time, and the injected strings. */
class ReaderViewTest {

    private static ReaderView.Prefs defaults() {
        return ReaderView.Prefs.normalized("serif", 20, 680, 1.6, "light");
    }

    @Test
    void prefsClampEveryAxisAndNormalizeNames() {
        ReaderView.Prefs p = ReaderView.Prefs.normalized("Comic Sans", 900, 10, 99.0, "neon");
        assertEquals(ReaderView.FONT_SERIF, p.font(), "an unknown font falls back to serif, Firefox's default");
        assertEquals(ReaderView.MAX_FONT_PX, p.fontSizePx());
        assertEquals(ReaderView.MIN_WIDTH_PX, p.widthPx());
        assertEquals(ReaderView.MAX_LINE_HEIGHT, p.lineHeight());
        assertEquals(ReaderView.THEME_LIGHT, p.theme());
        assertEquals(
                ReaderView.FONT_SANS,
                ReaderView.Prefs.normalized("SANS", 20, 680, 1.6, "SEPIA").font());
    }

    @Test
    void readingTimeIsARangeLikeFirefoxShows() {
        assertEquals("", ReaderView.readingTime(0));
        assertEquals("1 minute", ReaderView.readingTime(150), "below both speeds the range collapses");
        assertEquals("5–6 minutes", ReaderView.readingTime(1100)); // ceil(1100/260)=5, ceil(1100/200)=6
        // 240 words: one minute at the fast speed, two at the slow — the smallest visible range.
        assertEquals("1–2 minutes", ReaderView.readingTime(240));
        assertEquals("2 minutes", ReaderView.readingTime(300), "both speeds round to 2 — the range collapses");
    }

    @Test
    void styleCssCarriesEveryPreference() {
        String css = ReaderView.styleCss(ReaderView.Prefs.normalized("sans", 24, 760, 1.8, "sepia"));
        assertTrue(css.contains("font-size: 24px"));
        assertTrue(css.contains("max-width: 760px"));
        assertTrue(css.contains("line-height: 1.8"));
        assertTrue(css.contains("#f4ecd8"), "the sepia background is Firefox's");
        assertTrue(css.contains("Helvetica"), "sans maps to the sans stack");

        String dark = ReaderView.styleCss(ReaderView.Prefs.normalized("serif", 20, 680, 1.6, "dark"));
        assertTrue(dark.contains("#1c1b22"));
        assertTrue(dark.contains("Georgia"));
    }

    @Test
    void lineHeightNeverLeaksFloatingPointNoiseIntoCss() {
        // 1.2 + 0.2 * 3 is 1.7999999999999998 in doubles; the stylesheet must still say 1.8.
        double stepped = ReaderView.MIN_LINE_HEIGHT + ReaderView.LINE_HEIGHT_STEP * 3;
        String css = ReaderView.styleCss(ReaderView.Prefs.normalized("serif", 20, 680, stepped, "light"));
        assertTrue(
                css.contains("line-height: 1.8;"),
                css.lines().filter(l -> l.contains("line-height")).findFirst().orElse("?"));
    }

    @Test
    void bundledReadabilityIsTheRealThing() {
        String src = ReaderView.readabilitySource();
        assertTrue(src.contains("Copyright (c) 2010 Arc90 Inc"), "the Apache-2.0 header must travel with the code");
        assertTrue(src.contains("function Readability("), "the parser entry point");
        assertTrue(ReaderView.readerableSource().contains("isProbablyReaderable"));
    }

    @Test
    void enterScriptEscapesMetadataAsDataNotMarkup() {
        String script = ReaderView.enterScript("Archive \"quoted\" <b>", "3 minutes", defaults());
        assertFalse(script.contains("Archive \"quoted\" <b>"), "raw metadata must never be spliced in verbatim");
        assertTrue(script.contains("Archive \\\"quoted\\\" \\u003Cb>"), "quotes and angles arrive escaped");
        assertTrue(script.contains("textContent"), "metadata is assigned as text");
        assertTrue(script.contains("innerHTML = a.content"), "only Readability's own output is HTML");
        assertTrue(
                script.contains("link[rel~=\\\"stylesheet\\\"]") || script.contains("link[rel~=\"stylesheet\"]"),
                "the archive's stylesheets are removed — Reader View is a fresh page");
    }

    @Test
    void scriptsReturnOnlySmallValuesAcrossTheBridge() {
        assertTrue(ReaderView.extractScript().contains("return words.length"), "extraction returns a number");
        assertFalse(ReaderView.extractScript().contains("return article.content"), "content stays in the page");
        assertTrue(ReaderView.probeScript().contains("isProbablyReaderable(document)"));
    }

    @Test
    void sessionStateMachineAgainstAFakeRunner() {
        List<String> ran = new ArrayList<>();
        ReaderViewSession session = new ReaderViewSession(script -> {
            ran.add(script);
            if (script.contains("isProbablyReaderable(document)")) {
                return Boolean.TRUE;
            }
            if (script.contains("new Readability(")) {
                return 1100;
            }
            return Boolean.TRUE; // enter/prefs swaps succeed
        });

        assertTrue(session.probe());
        assertFalse(session.isActive());

        assertTrue(session.enter("Wikipedia", defaults()));
        assertTrue(session.isActive());
        assertEquals(1100, session.words());
        assertTrue(ran.getLast().contains("5–6 minutes"), "the header shows the computed reading time");

        session.applyPrefs(ReaderView.Prefs.normalized("sans", 22, 680, 1.6, "dark"));
        assertTrue(ran.getLast().contains("insula-rv-style"), "prefs restyle the existing page");

        session.pageChanged();
        assertFalse(session.isActive());
        session.applyPrefs(defaults());
        assertFalse(ran.getLast().contains("insula-rv-style") && session.isActive(), "no restyle after navigation");
    }

    @Test
    void sessionRefusesAnUnreadablePage() {
        ReaderViewSession session =
                new ReaderViewSession(script -> script.contains("new Readability(") ? -1 : Boolean.TRUE);
        assertFalse(session.enter("X", defaults()));
        assertFalse(session.isActive());
    }
}
