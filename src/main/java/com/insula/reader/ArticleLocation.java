package com.insula.reader;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Turns a served URL into the archive path that identifies an <em>article</em>.
 *
 * <p>The distinction matters because a URL carries more than the article. Two things ride along:
 *
 * <ul>
 *   <li>A <b>fragment</b>. Every table-of-contents link in a Wikipedia article is {@code #anchor},
 *       and a fragment navigation fires the engine's location listener exactly like a real one
 *       (measured). Treating that as a new article exits Reader View on a heading click, gives
 *       each anchor its own reading-position entry, and lets a restored position fight the anchor
 *       the reader just asked for.
 *   <li>A <b>query string</b>. Archive scripts append their own — TED's produce
 *       {@code ?lang=undefined} — so the same article is reached under several spellings and its
 *       saved position scatters across them.
 * </ul>
 *
 * <p>Pure, so the rule is unit-tested rather than inferred from behaviour.
 */
public final class ArticleLocation {

    private ArticleLocation() {}

    /**
     * The decoded archive path for a location served by {@code baseUrl}, or null when the location
     * is not ours (an external link, {@code about:blank}, a not-yet-started load).
     *
     * <p>The returned path still carries its {@code <token>/} prefix, which is what the caller
     * strips to reach the in-archive path.
     */
    public static String articlePath(String location, String baseUrl) {
        if (location == null || baseUrl == null || !location.startsWith(baseUrl)) {
            return null;
        }
        int marker = location.indexOf("/zim/");
        if (marker < 0) {
            return null;
        }
        String path = stripQueryAndFragment(location.substring(marker + 5));
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    /**
     * Drops {@code #fragment} and {@code ?query}. The fragment is cut first: a URL may carry both,
     * and a {@code ?} inside a fragment is part of the fragment, not a query.
     */
    public static String stripQueryAndFragment(String path) {
        if (path == null) {
            return null;
        }
        String result = path;
        int hash = result.indexOf('#');
        if (hash >= 0) {
            result = result.substring(0, hash);
        }
        int question = result.indexOf('?');
        if (question >= 0) {
            result = result.substring(0, question);
        }
        return result;
    }

    /** Whether two locations name the same article, differing only by fragment or query. */
    public static boolean sameArticle(String a, String b, String baseUrl) {
        String left = articlePath(a, baseUrl);
        return left != null && left.equals(articlePath(b, baseUrl));
    }
}
