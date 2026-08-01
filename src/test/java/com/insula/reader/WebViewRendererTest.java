package com.insula.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parts of the renderer — the toolkit-bound behaviour is covered by the FX tests. */
class WebViewRendererTest {

    @Test
    void quotesCssAsAJavaScriptStringLiteral() {
        assertEquals("\"body { color: red }\"", WebViewRenderer.quote("body { color: red }"));
    }

    @Test
    void escapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\"b\"", WebViewRenderer.quote("a\"b"));
        assertEquals("\"a\\\\b\"", WebViewRenderer.quote("a\\b"));
        assertEquals("\"a\\nb\"", WebViewRenderer.quote("a\nb"));
    }

    @Test
    void escapesAngleBracketsSoAClosingStyleTagCannotEscape() {
        // Injected CSS lands inside a <style> element. An unescaped "</style>" would end the
        // element and let the rest be parsed as markup.
        String quoted = WebViewRenderer.quote("x }</style><script>alert(1)</script>");
        assertFalse(quoted.contains("</style>"), quoted);
        assertFalse(quoted.contains("<script"), quoted);
        assertTrue(quoted.contains("\\u003C"));
    }
}
