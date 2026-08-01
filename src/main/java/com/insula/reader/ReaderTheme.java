package com.insula.reader;

import java.util.Locale;

/**
 * Generates the stylesheet Insula layers on top of whatever CSS an archive ships.
 *
 * <p>This is the feature the project brief singles out: "Kiwix's dark mode fights embedded
 * stylesheets and loses." Archived MediaWiki pages carry their own background colours, table
 * shading and inline styles, so a dark theme that merely sets {@code body { background: #111 }}
 * produces black text on white tables. Winning requires being specific and using
 * {@code !important} deliberately — which is normally bad practice, and is correct here precisely
 * because we are overriding a stylesheet we do not control and cannot edit.
 *
 * <p>Pure: the CSS is a function of the settings, so it is unit-tested without a browser.
 */
public final class ReaderTheme {

    /** How much of the archive's own styling to override. */
    public enum Mode {
        /** Show the article exactly as the archive styles it. */
        ORIGINAL,
        /** Keep the archive's light styling but apply typography and a readable column. */
        COMFORTABLE,
        /** Force a dark palette over the archive's own colours. */
        DARK
    }

    public static final int MIN_WIDTH = 480;
    public static final int MAX_WIDTH = 2000;
    public static final int UNLIMITED_WIDTH = MAX_WIDTH;

    private ReaderTheme() {}

    /**
     * @param contentWidth maximum content column in pixels; {@link #MAX_WIDTH} means unconstrained
     * @param fontScale 1.0 leaves the archive's own sizes alone
     */
    public static String css(Mode mode, int contentWidth, double fontScale) {
        if (mode == Mode.ORIGINAL && fontScale == 1.0 && contentWidth >= MAX_WIDTH) {
            return "";
        }
        StringBuilder css = new StringBuilder();
        if (mode == Mode.DARK) {
            css.append(darkCss());
        }
        if (contentWidth < MAX_WIDTH) {
            css.append(widthCss(clampWidth(contentWidth)));
        }
        if (fontScale != 1.0) {
            css.append(fontCss(fontScale));
        }
        if (mode != Mode.ORIGINAL) {
            css.append(readabilityCss());
        }
        return css.toString();
    }

    private static String darkCss() {
        // The wildcard rules are the point: archived pages set background and colour on arbitrary
        // elements (tables, infoboxes, inline styles), and anything left un-overridden shows as a
        // white block in a dark page. Images and video are deliberately NOT inverted — an inverted
        // photograph or map looks broken, which is the usual giveaway of a naive dark mode.
        // The blanket rule uses :where(), which contributes ZERO specificity, so the targeted rules
        // below still win. Written with :not() directly it scored (0,0,5) — higher than a bare
        // `th` — and silently defeated its own table shading, leaving every header and striped row
        // flat. Verified against a real article by reading back getComputedStyle.
        return """
               html { background: #14161a !important; color: #d7dae0 !important; }
               body { background-color: #14161a !important; color: #d7dae0 !important; }
               :where(*:not(img):not(video):not(canvas):not(svg):not(svg *):not(html):not(body)) {
                 background-color: transparent !important;
                 border-color: #33383f !important;
               }
               :where(body *:not(a):not(img):not(video):not(canvas):not(svg):not(svg *)) {
                 color: inherit !important;
               }
               th { background-color: #1c2027 !important; }
               tr:nth-child(even) td { background-color: #181b21 !important; }
               a, a * { color: #7aa7ff !important; }
               a:visited, a:visited * { color: #b28ce6 !important; }
               pre, code, kbd, samp { background-color: #1b1f26 !important; color: #d7dae0 !important; }
               hr { border-color: #33383f !important; }
               /* Photographs and diagrams keep their own colours but are dimmed slightly so a
                  white-background diagram does not glare out of a dark page. */
               img, video { filter: brightness(0.88) !important; }
               """;
    }

    private static String widthCss(int width) {
        return """
               body {
                 max-width: %dpx !important;
                 margin-left: auto !important;
                 margin-right: auto !important;
                 padding-left: 16px !important;
                 padding-right: 16px !important;
                 box-sizing: border-box !important;
               }
               """.formatted(width);
    }

    private static String fontCss(double scale) {
        return "html { font-size: %s%% !important; }%n".formatted(percent(scale));
    }

    private static String readabilityCss() {
        return """
               body { line-height: 1.6 !important; }
               p, li { line-height: 1.6 !important; }
               """;
    }

    /** Renders a scale as a percentage without a trailing ".0" that some engines dislike. */
    static String percent(double scale) {
        double value = scale * 100;
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    public static int clampWidth(int width) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));
    }

    public static Mode modeOf(String name) {
        if (name == null) {
            return Mode.ORIGINAL;
        }
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "dark" -> Mode.DARK;
            case "comfortable" -> Mode.COMFORTABLE;
            default -> Mode.ORIGINAL;
        };
    }

    public static String nameOf(Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
