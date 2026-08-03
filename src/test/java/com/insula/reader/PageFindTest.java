package com.insula.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The find script's shape, and the quoting that keeps a query from being code. */
class PageFindTest {

    @Test
    void aQueryIsDataRatherThanCode() {
        // An archived page is untrusted content and so is what someone types at it; a quote or a
        // backslash reaching the interpreter unescaped is a script injection into the article.
        assertEquals("\"say \\\"hi\\\"\"", PageFind.quote("say \"hi\""));
        assertEquals("\"a\\\\b\"", PageFind.quote("a\\b"));
        assertEquals("\"line\\nbreak\"", PageFind.quote("line\nbreak"));
    }

    @Test
    void theLineSeparatorsJavaScriptTreatsAsNewlinesAreEscaped() {
        // U+2028 and U+2029 terminate a JavaScript string literal even though Java sees one
        // ordinary character. Built numerically so this file holds no real line separator.
        String sep = "a" + (char) 0x2028 + "b";
        assertTrue(PageFind.quote(sep).contains("\\u2028"), PageFind.quote(sep));
        assertFalse(PageFind.quote(sep).contains(String.valueOf((char) 0x2028)));
        assertTrue(PageFind.quote("x" + (char) 0x2029).contains("\\u2029"));
    }

    @Test
    void theScriptDefinesTheWholeInterface() {
        String script = PageFind.installScript();
        for (String member : new String[] {"search", "clear", "next", "previous", "count"}) {
            assertTrue(script.contains(member + ":") || script.contains("function " + member), member);
        }
    }

    @Test
    void scriptAndEditableRegionsAreSkipped() {
        // Wrapping text inside a <script> changes what the page runs; marking inside an input
        // edits what somebody typed.
        String script = PageFind.installScript();
        assertTrue(script.contains("SCRIPT"), script);
        assertTrue(script.contains("isContentEditable"), script);
    }

    @Test
    void theMarkClassesReachTheStylesheet() {
        assertTrue(PageFind.css().contains(PageFind.MARK_CLASS));
        assertTrue(PageFind.css().contains(PageFind.CURRENT_CLASS));
    }

    @Test
    void theModuloInTheScriptSurvivedFormatting() {
        // The script is built with String.formatted, so a literal % has to be doubled — get it
        // wrong and the wrap-around arithmetic silently becomes something else.
        assertTrue(PageFind.installScript().contains("% hits.length"));
        assertFalse(PageFind.installScript().contains("%%"));
    }
}
