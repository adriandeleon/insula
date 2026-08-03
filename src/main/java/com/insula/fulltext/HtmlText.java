package com.insula.fulltext;

/**
 * The readable text of an archived HTML page.
 *
 * <p>Hand-rolled rather than jsoup, for the reason the ZIM parser is hand-rolled: this needs to be
 * correct on the machine-generated HTML a ZIM actually contains, not on the arbitrary broken markup
 * of the open web, and a dependency that has to travel through jlink for a job this size is a poor
 * trade. It is a single forward pass with no tree, so a fifty-megabyte article costs one scan.
 *
 * <p>What it is for matters: the output is fed to an index, never rendered. So it does not need to
 * be pretty — it needs every word that is really on the page, none that are not, and word
 * boundaries where the markup implied them. That last part is the one that bites: dropping tags
 * without leaving a space turns {@code <li>Cat</li><li>Dog</li>} into "CatDog", one token that
 * matches neither search.
 */
public final class HtmlText {

    /** Beyond this a "page" is not prose — a data blob in a script, or a generated table dump. */
    private static final int MAX_CHARS = 2_000_000;

    private HtmlText() {}

    public static String extract(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(html.length(), 8192));
        int i = 0;
        int n = html.length();
        while (i < n && out.length() < MAX_CHARS) {
            char c = html.charAt(i);
            if (c != '<') {
                append(out, c);
                i++;
                continue;
            }
            int close = html.indexOf('>', i);
            if (close < 0) {
                break; // an unterminated tag at the end: nothing readable follows
            }
            String tag = tagName(html, i);
            // script and style hold code, not prose, and comments hold neither. Their content is
            // skipped entirely rather than stripped tag by tag, which would leak the code in.
            if (tag.equals("script") || tag.equals("style")) {
                int end = indexOfClosing(html, close + 1, tag);
                i = end < 0 ? n : end;
                out.append(' ');
                continue;
            }
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                i = end < 0 ? n : end + 3;
                continue;
            }
            // Every tag becomes a space. Inline tags produce a harmless extra gap; block tags
            // produce the word boundary that stops two list items becoming one token.
            out.append(' ');
            i = close + 1;
        }
        return collapse(out);
    }

    /** Entities are decoded for the handful that carry meaning; the rest become spaces. */
    private static void append(StringBuilder out, char c) {
        out.append(c == ' ' ? ' ' : c);
    }

    private static String tagName(String html, int lt) {
        int i = lt + 1;
        if (i < html.length() && html.charAt(i) == '/') {
            i++;
        }
        int start = i;
        while (i < html.length() && (Character.isLetterOrDigit(html.charAt(i)))) {
            i++;
        }
        return html.substring(start, i).toLowerCase(java.util.Locale.ROOT);
    }

    /** Where {@code </tag>} ends, or -1. */
    private static int indexOfClosing(String html, int from, String tag) {
        String needle = "</" + tag;
        int at = html.toLowerCase(java.util.Locale.ROOT).indexOf(needle, from);
        if (at < 0) {
            return -1;
        }
        int close = html.indexOf('>', at);
        return close < 0 ? -1 : close + 1;
    }

    /**
     * Squeezes runs of whitespace, and decodes the five entities that would otherwise show up as
     * words. Everything else that looks like an entity is left alone: an index does not care
     * whether it holds "&copy;" or "©", and a full entity table is a lot of code for that.
     */
    private static String collapse(StringBuilder raw) {
        String text = raw.toString()
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        StringBuilder out = new StringBuilder(text.length());
        boolean lastWasSpace = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    out.append(' ');
                    lastWasSpace = true;
                }
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        return out.toString().strip();
    }
}
