package com.insula.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reader View — the Firefox-style distilled article page.
 *
 * <p>This is a different feature from {@link ReaderTheme}: the theme layers CSS over the page the
 * archive shipped, while Reader View <em>extracts</em> the article (Mozilla's Readability, the
 * same engine behind Firefox's about:reader, vendored at 0.6.0) and rebuilds the document as a
 * clean column — title, source, reading time, then nothing but the article.
 *
 * <p>Everything here is a pure function from preferences to strings (CSS, JavaScript), so the
 * shape of what gets injected is unit-testable without a browser. The scripts follow two rules:
 *
 * <ul>
 *   <li><b>Big payloads never cross the JS↔Java bridge.</b> The parsed article stays in
 *       {@code window.__insulaArticle}; extraction returns only a word count (a number), and the
 *       enter script reads title/byline/content from the window object. Round-tripping a
 *       megabyte of HTML through {@code executeScript} strings would be pure waste.
 *   <li><b>Article metadata is inserted via {@code textContent}, never {@code innerHTML}</b> — a
 *       title is data. Only {@code article.content} (Readability's sanitized output, the same
 *       thing Firefox renders) is assigned as HTML.
 * </ul>
 */
public final class ReaderView {

    public static final String FONT_SERIF = "serif";
    public static final String FONT_SANS = "sans";

    public static final String THEME_LIGHT = "light";
    public static final String THEME_SEPIA = "sepia";
    public static final String THEME_DARK = "dark";

    public static final int MIN_FONT_PX = 12;
    public static final int MAX_FONT_PX = 32;
    public static final int FONT_STEP_PX = 2;

    public static final int MIN_WIDTH_PX = 440;
    public static final int MAX_WIDTH_PX = 1160;
    public static final int WIDTH_STEP_PX = 80;

    public static final double MIN_LINE_HEIGHT = 1.2;
    public static final double MAX_LINE_HEIGHT = 2.4;
    public static final double LINE_HEIGHT_STEP = 0.2;

    /** Reading-speed band for the time estimate; Firefox likewise shows a slow–fast range. */
    static final int SLOW_WPM = 200;

    static final int FAST_WPM = 260;

    private static volatile String readabilitySource;
    private static volatile String readerableSource;

    private ReaderView() {}

    /** Typography preferences. Construct via {@link #normalized} so out-of-range values clamp. */
    public record Prefs(String font, int fontSizePx, int widthPx, double lineHeight, String theme) {

        public static Prefs normalized(String font, int fontSizePx, int widthPx, double lineHeight, String theme) {
            return new Prefs(
                    FONT_SANS.equalsIgnoreCase(font) ? FONT_SANS : FONT_SERIF,
                    clamp(fontSizePx, MIN_FONT_PX, MAX_FONT_PX),
                    clamp(widthPx, MIN_WIDTH_PX, MAX_WIDTH_PX),
                    Math.max(MIN_LINE_HEIGHT, Math.min(MAX_LINE_HEIGHT, lineHeight)),
                    normalizeTheme(theme));
        }

        public static String normalizeTheme(String theme) {
            if (theme == null) {
                return THEME_LIGHT;
            }
            return switch (theme.strip().toLowerCase(Locale.ROOT)) {
                case THEME_SEPIA -> THEME_SEPIA;
                case THEME_DARK -> THEME_DARK;
                default -> THEME_LIGHT;
            };
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /**
     * "4–6 minutes" from a word count — a range because reading speed varies more than a single
     * confident number can honestly cover; Firefox presents it the same way.
     */
    public static String readingTime(int words) {
        if (words <= 0) {
            return "";
        }
        int fast = Math.max(1, (int) Math.ceil(words / (double) FAST_WPM));
        int slow = Math.max(1, (int) Math.ceil(words / (double) SLOW_WPM));
        return fast == slow ? fast + (fast == 1 ? " minute" : " minutes") : fast + "–" + slow + " minutes";
    }

    /**
     * Runs Readability over a clone of the current document and parks the result on
     * {@code window.__insulaArticle}. Evaluates to the article's word count, or −1 when the page
     * has no extractable article.
     */
    public static String extractScript() {
        return readabilitySource() + "\n" + """
                (function() {
                  try {
                    var article = new Readability(document.cloneNode(true)).parse();
                    if (!article || !article.content) { return -1; }
                    window.__insulaArticle = article;
                    var text = article.textContent || "";
                    var words = text.trim().split(/\\s+/).filter(function(w) { return w.length > 0; });
                    return words.length;
                  } catch (e) { return -1; }
                })();
                """;
    }

    /**
     * Mozilla's quick suitability check — the same call that decides whether Firefox lights up
     * the reader icon. Evaluates to a boolean.
     */
    public static String probeScript() {
        return readerableSource() + "\n" + """
                (function() {
                  try { return isProbablyReaderable(document); } catch (e) { return false; }
                })();
                """;
    }

    /**
     * Swaps the live document for the reader shell. The archive's own stylesheets are removed
     * first — Reader View is a fresh page, exactly as about:reader is a fresh page, not another
     * layer fighting the archive's CSS. The document URL never changes, so the article's
     * relative image paths keep resolving; exit is simply a reload.
     */
    public static String enterScript(String sourceLabel, String timeLabel, Prefs prefs) {
        return """
                (function() {
                  var a = window.__insulaArticle;
                  if (!a) { return false; }
                  var stale = document.querySelectorAll('link[rel~="stylesheet"], style');
                  for (var i = 0; i < stale.length; i++) { stale[i].parentNode.removeChild(stale[i]); }

                  var style = document.createElement('style');
                  style.id = 'insula-rv-style';
                  style.textContent = %s;
                  document.head.appendChild(style);

                  var body = document.body;
                  body.innerHTML = '';
                  body.className = 'insula-rv';
                  // The old document's inline styles on the root elements would outrank every
                  // stylesheet rule (proven by a body background that refused to change); the
                  // elements are inherited by the shell, so their attributes must not be.
                  body.removeAttribute('style');
                  document.documentElement.removeAttribute('style');
                  document.documentElement.className = '';

                  var container = document.createElement('div');
                  container.className = 'rv-container';

                  var source = document.createElement('div');
                  source.className = 'rv-source';
                  source.textContent = %s;
                  container.appendChild(source);

                  var title = document.createElement('h1');
                  title.className = 'rv-title';
                  title.textContent = a.title || '';
                  container.appendChild(title);

                  if (a.byline) {
                    var byline = document.createElement('div');
                    byline.className = 'rv-byline';
                    byline.textContent = a.byline;
                    container.appendChild(byline);
                  }

                  var meta = document.createElement('div');
                  meta.className = 'rv-meta';
                  meta.textContent = %s;
                  container.appendChild(meta);

                  container.appendChild(document.createElement('hr'));

                  var content = document.createElement('div');
                  content.className = 'rv-content';
                  content.innerHTML = a.content;
                  container.appendChild(content);

                  body.appendChild(container);
                  window.scrollTo(0, 0);
                  return true;
                })();
                """.formatted(
                        WebViewRenderer.quote(styleCss(prefs)),
                        WebViewRenderer.quote(sourceLabel == null ? "" : sourceLabel),
                        WebViewRenderer.quote(timeLabel == null ? "" : timeLabel));
    }

    /** Restyles an already-entered reader page in place — what the Aa panel's buttons run. */
    public static String prefsScript(Prefs prefs) {
        return """
                (function() {
                  var style = document.getElementById('insula-rv-style');
                  if (!style) { return false; }
                  style.textContent = %s;
                  return true;
                })();
                """.formatted(WebViewRenderer.quote(styleCss(prefs)));
    }

    /** The whole reader stylesheet for one preference set. Palette values follow Firefox's. */
    public static String styleCss(Prefs prefs) {
        Prefs p =
                Prefs.normalized(prefs.font(), prefs.fontSizePx(), prefs.widthPx(), prefs.lineHeight(), prefs.theme());
        String fontStack =
                FONT_SANS.equals(p.font()) ? "Helvetica, Arial, sans-serif" : "Georgia, 'Times New Roman', serif";
        String bg;
        String fg;
        String link;
        String border;
        switch (p.theme()) {
            case THEME_SEPIA -> {
                bg = "#f4ecd8";
                fg = "#5b4636";
                link = "#0060df";
                border = "#d9cba9";
            }
            case THEME_DARK -> {
                bg = "#1c1b22";
                fg = "#fbfbfe";
                link = "#00ddff";
                border = "#52525e";
            }
            default -> {
                bg = "#ffffff";
                fg = "#15141a";
                link = "#0060df";
                border = "#d7d7db";
            }
        }
        return """
               html, body.insula-rv { background: %s; color: %s; margin: 0; padding: 0; }
               body.insula-rv { font-family: %s; font-size: %dpx; line-height: %s; }
               .rv-container { max-width: %dpx; margin: 0 auto; padding: 48px 24px 64px 24px; }
               .rv-source { font-size: 0.8em; opacity: 0.66; margin-bottom: 14px; }
               .rv-title { font-size: 1.6em; line-height: 1.25; margin: 0 0 6px 0; }
               .rv-byline, .rv-meta { font-size: 0.85em; opacity: 0.66; margin: 2px 0; }
               .rv-container hr { border: none; border-top: 1px solid %s; margin: 18px 0 26px 0; }
               .rv-content img, .rv-content video { max-width: 100%%; height: auto; }
               .rv-content figure { margin: 1.2em 0; }
               .rv-content figcaption { font-size: 0.85em; opacity: 0.7; }
               .rv-content a { color: %s; }
               .rv-content pre { overflow-x: auto; padding: 10px; border: 1px solid %s; }
               .rv-content code, .rv-content pre { font-family: monospace; font-size: 0.9em; }
               .rv-content blockquote { border-left: 3px solid %s; margin-left: 0; padding-left: 16px; opacity: 0.9; }
               .rv-content table { border-collapse: collapse; max-width: 100%%; }
               .rv-content th, .rv-content td { border: 1px solid %s; padding: 5px 9px; }
               .rv-content h1, .rv-content h2, .rv-content h3 { line-height: 1.3; }
               """.formatted(
                        bg,
                        fg,
                        fontStack,
                        p.fontSizePx(),
                        trimNumber(p.lineHeight()),
                        p.widthPx(),
                        border,
                        link,
                        border,
                        border,
                        border);
    }

    /** "1.6", never "1.6000000000000001" — floating-point noise has no place in a stylesheet. */
    static String trimNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String readabilitySource() {
        String cached = readabilitySource;
        if (cached == null) {
            cached = loadResource("Readability.js");
            readabilitySource = cached;
        }
        return cached;
    }

    static String readerableSource() {
        String cached = readerableSource;
        if (cached == null) {
            cached = loadResource("Readability-readerable.js");
            readerableSource = cached;
        }
        return cached;
    }

    private static String loadResource(String name) {
        try (InputStream in = ReaderView.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled resource: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + name, e);
        }
    }
}
