package com.insula.fulltext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an archived page reduces to when all that matters is its words. */
class HtmlTextTest {

    @Test
    void tagsGoAndTheProseStays() {
        assertEquals("Hello world", HtmlText.extract("<p>Hello <b>world</b></p>"));
    }

    @Test
    void everyTagLeavesAWordBoundary() {
        // The one that bites: without a space, two list items become one token that matches
        // neither search.
        assertEquals("Cat Dog", HtmlText.extract("<ul><li>Cat</li><li>Dog</li></ul>"));
        assertEquals("one two", HtmlText.extract("<td>one</td><td>two</td>"));
    }

    @Test
    void scriptAndStyleContentIsNotProse() {
        String html = "<p>Before</p><script>var x = 'secret';</script><style>p{color:red}</style><p>After</p>";
        String text = HtmlText.extract(html);
        assertTrue(text.contains("Before"), text);
        assertTrue(text.contains("After"), text);
        assertFalse(text.contains("secret"), "script bodies are code, not words: " + text);
        assertFalse(text.contains("color"), text);
    }

    @Test
    void commentsAreNotWords() {
        assertEquals("Visible", HtmlText.extract("<p>Visible<!-- hidden note --></p>"));
    }

    @Test
    void whitespaceIsSqueezedSoTokensAreClean() {
        assertEquals("a b c", HtmlText.extract("<p>a</p>\n\n   <p>b</p>\t<p>c</p>"));
        assertEquals("nbsp here", HtmlText.extract("nbsp&nbsp;here"));
    }

    @Test
    void theEntitiesThatWouldReadAsWordsAreDecoded() {
        assertEquals("Rock & Roll", HtmlText.extract("Rock &amp; Roll"));
        assertEquals("a < b", HtmlText.extract("a &lt; b"));
    }

    @Test
    void anUnterminatedTagEndsTheScanRatherThanLoops() {
        assertEquals("Before", HtmlText.extract("<p>Before</p><div class=\"never closed"));
    }

    @Test
    void nothingInGivesNothingOut() {
        assertEquals("", HtmlText.extract(null));
        assertEquals("", HtmlText.extract(""));
        assertEquals("", HtmlText.extract("<div></div>"));
    }

    @Test
    void aRealisticMediaWikiFragmentReadsAsSentences() {
        String html = """
                <div class="mw-body"><h1 id="firstHeading">Ray Charles</h1>
                <div id="mw-content-text"><p><b>Ray Charles Robinson</b> (September 23, 1930 &ndash;
                June 10, 2004) was an American <a href="Singer">singer</a>.</p>
                <table class="infobox"><tr><th>Born</th><td>1930</td></tr></table></div></div>
                """;
        String text = HtmlText.extract(html);
        assertTrue(text.contains("Ray Charles Robinson"), text);
        assertTrue(text.contains("was an American singer"), text);
        assertTrue(text.contains("Born 1930"), "table cells keep their boundary: " + text);
    }
}
