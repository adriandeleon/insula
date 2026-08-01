package com.insula.reader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parts of the renderer — the toolkit-bound behaviour is covered by the FX tests. */
class WebViewRendererTest {

    @Test
    void stylesheetIsEncodedAsADataUri() {
        // Regression: the reader stylesheet used to be appended by script after navigation, which
        // landed *after* the new document had painted — so every link click flashed unstyled and
        // then reflowed. A user stylesheet is applied during parsing instead, and persists across
        // navigations without re-injection.
        String uri = WebViewRenderer.dataUri("body { color: red }");
        assertTrue(uri.startsWith("data:text/css;base64,"), uri);
        String decoded = new String(
                java.util.Base64.getDecoder().decode(uri.substring("data:text/css;base64,".length())),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("body { color: red }", decoded);
    }

    @Test
    void nonAsciiCssSurvivesEncoding() {
        String css = "a::after { content: '→ ünïcode' }";
        String decoded = new String(
                java.util.Base64.getDecoder()
                        .decode(WebViewRenderer.dataUri(css).substring("data:text/css;base64,".length())),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(css, decoded);
    }

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
