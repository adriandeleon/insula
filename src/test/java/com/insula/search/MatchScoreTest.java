package com.insula.search;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchScoreTest {

    private static int score(String query, String title) {
        return MatchScore.score(query.toLowerCase(Locale.ROOT), title.toLowerCase(Locale.ROOT), title.length());
    }

    /** Titles sorted best-first for a query, as the UI would show them. */
    private static List<String> ranked(String query, String... titles) {
        return java.util.Arrays.stream(titles)
                .filter(t -> score(query, t) != MatchScore.NO_MATCH)
                .sorted(Comparator.comparingInt((String t) -> score(query, t)).reversed())
                .toList();
    }

    @Test
    void exactBeatsPrefixBeatsWordStartBeatsContains() {
        assertEquals(
                List.of("Paris", "Paris Metro", "New Paris", "Comparison"),
                ranked("paris", "Comparison", "New Paris", "Paris Metro", "Paris"));
    }

    @Test
    void shorterTitlesWinWithinATier() {
        // "Paris" is a better answer than "Paris Metro rolling stock" for the same tier.
        assertTrue(score("paris", "Paris Metro") > score("paris", "Paris Metro rolling stock"));
    }

    @Test
    void aLongTitleNeverOutranksABetterTier() {
        // The length penalty must not let a "contains" beat a "prefix", however long the prefix is.
        String longPrefix = "Paris" + " x".repeat(200);
        assertTrue(score("paris", longPrefix) > score("paris", "Comparison"));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals(score("paris", "Paris"), score("PARIS", "paris"));
    }

    @Test
    void matchesAtWordBoundariesInsideTheTitle() {
        // A word-start match beats a match buried mid-word...
        assertTrue(score("york", "New York") > score("york", "Foo yorkshire"));
        assertTrue(score("york", "New-York") != MatchScore.NO_MATCH);
        assertTrue(score("metro", "Paris (Metro)") != MatchScore.NO_MATCH);
    }

    @Test
    void aTitleStartingWithTheQueryStillWinsOverAWordStartMatch() {
        // ...but a real prefix outranks both, as in Spotlight or a file finder: typing "york"
        // surfaces "Yorkshire" ahead of "New York". Deliberate — the first characters a user
        // types are the strongest signal they have.
        assertTrue(score("york", "Yorkshire") > score("york", "New York"));
    }

    @Test
    void matchesAnAcronymLikeSubsequence() {
        assertTrue(MatchScore.isSubsequence("nyk", "new york"));
        assertTrue(score("nyk", "New York") != MatchScore.NO_MATCH);
        assertFalse(MatchScore.isSubsequence("nyz", "new york"));
    }

    @Test
    void toleratesASingleTypo() {
        assertTrue(score("wikpedia", "Wikipedia") != MatchScore.NO_MATCH, "deletion");
        assertTrue(score("wikipedia", "Wikpedia") != MatchScore.NO_MATCH, "insertion");
        assertTrue(score("wikapedia", "Wikipedia") != MatchScore.NO_MATCH, "substitution");
    }

    @Test
    void toleratesTransposedCharacters() {
        // Swapping two adjacent keys is one of the commonest typing errors, and plain Levenshtein
        // counts it as two edits — so it needs handling explicitly.
        assertTrue(score("teh", "The") != MatchScore.NO_MATCH);
        assertTrue(score("Польскяа", "Польская кухня") != MatchScore.NO_MATCH);
        assertTrue(MatchScore.withinOneEdit("wikipdeia", "wikipedia"));
    }

    @Test
    void typoToleranceWorksAgainstAPrefixOfALongerTitle() {
        // While typing, the full title is usually much longer than the query.
        assertTrue(score("wikpedia", "Wikipedia (disambiguation)") != MatchScore.NO_MATCH);
    }

    @Test
    void aTypoRanksBelowEveryRealMatch() {
        assertTrue(score("wikipedia", "Wikipedia") > score("wikpedia", "Wikipedia"));
        assertTrue(score("pedia", "Wikipedia") > score("wikapedia", "Wikipedia"));
    }

    @Test
    void twoTyposAreNotAMatch() {
        // "wkpdia" would be rejected as a typo but IS a subsequence of "wikipedia", so it still
        // matches — that is the intended fzf-style behaviour, just at a lower tier.
        assertTrue(score("wkpdia", "Wikipedia") != MatchScore.NO_MATCH);
        assertTrue(score("wikipedia", "Wikipedia") > score("wkpdia", "Wikipedia"));

        // Two substitutions, and not a subsequence either: genuinely not a match.
        assertEquals(MatchScore.NO_MATCH, score("wikapedxa", "Wikipedia"));
        assertFalse(MatchScore.withinOneEdit("abcd", "wxyz"));
    }

    @Test
    void unrelatedTitlesDoNotMatch() {
        assertEquals(MatchScore.NO_MATCH, score("zebra", "Paris"));
        assertEquals(MatchScore.NO_MATCH, score("paris", ""));
        assertEquals(MatchScore.NO_MATCH, score("", "Paris"));
    }

    @Test
    void handlesNonAsciiTitles() {
        assertTrue(score("уикипедия", "Уикипедия") != MatchScore.NO_MATCH);
        assertTrue(score("перш", "Першая старонка") != MatchScore.NO_MATCH);
    }

    @Test
    void theTierIsRecoverableFromTheScoreSoTheCaptionCannotContradictTheRanking() {
        assertEquals(MatchScore.Tier.EXACT_MATCH, MatchScore.tierOf(MatchScore.score("paris", "paris", 5)));
        assertEquals(MatchScore.Tier.STARTS_WITH, MatchScore.tierOf(MatchScore.score("par", "paris", 5)));
        assertEquals(MatchScore.Tier.WORD_START, MatchScore.tierOf(MatchScore.score("york", "new york", 8)));
        assertEquals(MatchScore.Tier.CONTAINS_TEXT, MatchScore.tierOf(MatchScore.score("ari", "paris", 5)));
        assertEquals(MatchScore.Tier.FUZZY, MatchScore.tierOf(MatchScore.score("nyk", "new york", 8)));
        assertEquals(MatchScore.Tier.NONE, MatchScore.tierOf(MatchScore.NO_MATCH));
    }

    @Test
    void theLongestPossibleTitleStillStaysInsideItsOwnTier() {
        // The length penalty is bounded below a band's width on purpose; if that ever stopped
        // holding, a long exact match would be captioned as merely "starts with".
        int worstCase = MatchScore.score("paris", "paris", Integer.MAX_VALUE);
        assertEquals(MatchScore.Tier.EXACT_MATCH, MatchScore.tierOf(worstCase));
        assertEquals(
                MatchScore.Tier.STARTS_WITH, MatchScore.tierOf(MatchScore.score("par", "paris", Integer.MAX_VALUE)));
    }
}
