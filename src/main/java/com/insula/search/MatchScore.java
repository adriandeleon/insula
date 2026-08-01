package com.insula.search;

/**
 * Scores how well a title matches a query. Higher is better; {@link #NO_MATCH} means "don't show".
 *
 * <p>Pure and allocation-free on the hot path: this runs against every title in every open archive
 * on each keystroke — hundreds of thousands of comparisons — so it takes pre-lowercased input and
 * never builds intermediate strings.
 *
 * <p>The tiers are ordered by how confident we are that the user meant this result:
 * an exact title beats a prefix, a prefix beats a match starting at a word boundary ("york"
 * finding "New York"), that beats a match anywhere inside, and last comes a subsequence or a
 * single typo. Within a tier, shorter titles win — "Paris" is a better answer for "paris" than
 * "Paris Metro rolling stock".
 */
public final class MatchScore {

    public static final int NO_MATCH = 0;

    private static final int EXACT = 1_000_000;
    private static final int PREFIX = 900_000;
    private static final int WORD_PREFIX = 800_000;
    private static final int CONTAINS = 700_000;
    private static final int SUBSEQUENCE = 500_000;
    private static final int TYPO = 400_000;

    /** Longer titles are worse answers, but the penalty must never cross a tier boundary. */
    private static final int MAX_LENGTH_PENALTY = 50_000;

    /** Edit distance is O(n·m); only attempt it for short queries where a typo is plausible. */
    private static final int MAX_TYPO_QUERY = 16;

    private MatchScore() {}

    /**
     * @param query the search text, already lower-cased and stripped
     * @param title the candidate title, already lower-cased
     * @param originalLength length of the untouched title, for the length penalty
     */
    public static int score(String query, String title, int originalLength) {
        if (query.isEmpty() || title.isEmpty()) {
            return NO_MATCH;
        }
        int penalty = Math.min(originalLength, MAX_LENGTH_PENALTY);

        if (title.equals(query)) {
            return EXACT - penalty;
        }
        if (title.startsWith(query)) {
            return PREFIX - penalty;
        }
        int at = title.indexOf(query);
        if (at > 0) {
            return (isWordStart(title, at) ? WORD_PREFIX : CONTAINS) - penalty;
        }
        if (isSubsequence(query, title)) {
            return SUBSEQUENCE - penalty;
        }
        if (query.length() <= MAX_TYPO_QUERY && withinOneEdit(query, title)) {
            return TYPO - penalty;
        }
        return NO_MATCH;
    }

    private static boolean isWordStart(String title, int index) {
        char before = title.charAt(index - 1);
        return before == ' ' || before == '_' || before == '-' || before == '(' || before == '/';
    }

    /** All of {@code query}'s characters appear in {@code title} in order — "nyk" → "new york". */
    static boolean isSubsequence(String query, String title) {
        int q = 0;
        for (int t = 0; t < title.length() && q < query.length(); t++) {
            if (title.charAt(t) == query.charAt(q)) {
                q++;
            }
        }
        return q == query.length();
    }

    /**
     * Whether the query is within one insertion, deletion, substitution or <b>transposition</b> of
     * a <em>prefix</em> of the title (Damerau–Levenshtein distance 1).
     *
     * <p>Transposition is included because swapping two adjacent characters is one of the most
     * common typing mistakes, and plain Levenshtein scores it as two edits — so "Польскяа" would
     * fail to find "Польская" without it.
     *
     * <p>Comparing against a prefix rather than the whole title is what makes typo tolerance
     * useful while typing: "wikpedia" should find "Wikipedia (disambiguation)" even though the
     * full titles are nowhere near each other.
     */
    static boolean withinOneEdit(String query, String title) {
        // The matching prefix may be one shorter, the same length, or one longer than the query
        // depending on whether the edit was an insertion, substitution or deletion. Testing a
        // single fixed window fails whenever the title continues past it — "польскяа" against
        // "польская кухня" would have to consume the trailing space to succeed.
        for (int length = query.length() - 1; length <= query.length() + 1; length++) {
            if (length >= 0 && length <= title.length() && withinOneEditOf(query, title.substring(0, length))) {
                return true;
            }
        }
        return false;
    }

    /** Damerau–Levenshtein distance between {@code query} and {@code head} is at most 1. */
    private static boolean withinOneEditOf(String query, String head) {
        if (Math.abs(head.length() - query.length()) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        boolean usedEdit = false;
        while (i < query.length() && j < head.length()) {
            if (query.charAt(i) == head.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (usedEdit) {
                return false;
            }
            usedEdit = true;
            boolean swapped = i + 1 < query.length()
                    && j + 1 < head.length()
                    && query.charAt(i) == head.charAt(j + 1)
                    && query.charAt(i + 1) == head.charAt(j);
            if (swapped) {
                i += 2; // transposition of two adjacent characters
                j += 2;
            } else if (query.length() > head.length()) {
                i++; // deletion from the query
            } else if (query.length() < head.length()) {
                j++; // insertion into the query
            } else {
                i++; // substitution
                j++;
            }
        }
        // Whatever is left over counts as the single edit, if one is still available.
        return usedEdit ? i == query.length() && j == head.length() : (query.length() - i) + (head.length() - j) <= 1;
    }
}
