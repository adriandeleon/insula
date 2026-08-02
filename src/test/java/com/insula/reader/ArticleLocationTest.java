package com.insula.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleLocationTest {

    private static final String BASE = "http://127.0.0.1:8080";

    @Test
    void aPlainArticleUrlYieldsItsPath() {
        assertEquals("z1/C/Walt_Disney", ArticleLocation.articlePath(BASE + "/zim/z1/C/Walt_Disney", BASE));
    }

    @Test
    void aTableOfContentsAnchorIsTheSameArticle() {
        // Every Wikipedia heading link is a fragment, and the engine reports one exactly like a
        // real navigation — so this is what keeps a heading click from exiting Reader View.
        String plain = BASE + "/zim/z1/C/Walt_Disney";
        String anchored = BASE + "/zim/z1/C/Walt_Disney#Early_life";
        assertEquals(ArticleLocation.articlePath(plain, BASE), ArticleLocation.articlePath(anchored, BASE));
        assertTrue(ArticleLocation.sameArticle(plain, anchored, BASE));
    }

    @Test
    void anArchiveScriptsQueryStringIsTheSameArticle() {
        // Observed on TED's archive, whose own script navigates to "?lang=undefined"; without this
        // the article's saved reading position scatters across spellings of the same page.
        String plain = BASE + "/zim/z9/C/how-ai-is-unlocking-the-secrets";
        String queried = plain + "?lang=undefined";
        assertEquals(ArticleLocation.articlePath(plain, BASE), ArticleLocation.articlePath(queried, BASE));
        assertTrue(ArticleLocation.sameArticle(plain, queried, BASE));
    }

    @Test
    void queryAndFragmentTogetherBothGo() {
        assertEquals("z1/C/A", ArticleLocation.articlePath(BASE + "/zim/z1/C/A?lang=undefined#Section", BASE));
        // A "?" inside a fragment belongs to the fragment, so the fragment is cut first.
        assertEquals("z1/C/A", ArticleLocation.articlePath(BASE + "/zim/z1/C/A#what?", BASE));
    }

    @Test
    void percentEncodingIsDecodedSoKeysMatchWhatTheArchiveCallsIt() {
        assertEquals("z1/C/Ray Charles", ArticleLocation.articlePath(BASE + "/zim/z1/C/Ray%20Charles", BASE));
    }

    @Test
    void differentArticlesStayDifferent() {
        assertFalse(ArticleLocation.sameArticle(BASE + "/zim/z1/C/A", BASE + "/zim/z1/C/B", BASE));
        // Same path in two archives is not the same article either.
        assertFalse(ArticleLocation.sameArticle(BASE + "/zim/z1/C/A", BASE + "/zim/z2/C/A", BASE));
    }

    @Test
    void locationsThatAreNotOursYieldNothing() {
        assertNull(ArticleLocation.articlePath("https://en.wikipedia.org/wiki/X", BASE));
        assertNull(ArticleLocation.articlePath("about:blank", BASE));
        assertNull(ArticleLocation.articlePath(null, BASE));
        assertNull(ArticleLocation.articlePath(BASE + "/file/f1", BASE), "a served transcode is not an article");
        assertFalse(ArticleLocation.sameArticle("about:blank", "about:blank", BASE), "null is never 'the same'");
    }
}
