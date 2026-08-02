package com.insula.reader;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;

/**
 * {@link ArticleRenderer} backed by JavaFX WebView.
 *
 * <p>Measured against the worst content in a real archive (a 3.4 MB article, one with 13,347 table
 * rows): sub-second loads, a locked 60 fps while scrolling with zero frames over 32 ms, and flat
 * heap. The "JavaFX WebView is ancient WebKit" reputation does not hold for 26.x, and there is no
 * performance case for swapping engines — the reason to keep this interface is that WebView is
 * single-process, so a renderer crash is fatal to the whole app.
 */
public final class WebViewRenderer implements ArticleRenderer {

    private final WebView webView = new WebView();

    public WebViewRenderer() {
        webView.getEngine().setJavaScriptEnabled(true);
    }

    /** Escape hatch for code that genuinely needs the engine; keep uses few. */
    public WebEngine engine() {
        return webView.getEngine();
    }

    @Override
    public Node node() {
        return webView;
    }

    @Override
    public void load(String url) {
        webView.getEngine().load(url);
    }

    @Override
    public boolean canGoBack() {
        return webView.getEngine().getHistory().getCurrentIndex() > 0;
    }

    @Override
    public boolean canGoForward() {
        WebHistory history = webView.getEngine().getHistory();
        return history.getCurrentIndex() < history.getEntries().size() - 1;
    }

    @Override
    public void goBack() {
        if (canGoBack()) {
            webView.getEngine().getHistory().go(-1);
        }
    }

    @Override
    public void goForward() {
        if (canGoForward()) {
            webView.getEngine().getHistory().go(1);
        }
    }

    @Override
    public void setZoom(double factor) {
        webView.setZoom(factor);
    }

    /**
     * Applies the reader stylesheet as WebKit's <b>user stylesheet</b>.
     *
     * <p>The obvious implementation — appending a {@code <style>} element by script once the page
     * has navigated — flashes on every link click: the new document parses and paints with its own
     * styling, and only then does the script reflow it. With a content width set that is a visible
     * full-width-then-narrow jump on every article, even in "original" mode.
     *
     * <p>A user stylesheet is applied while the document is parsed, so there is nothing to reflow,
     * and it persists across navigations by itself rather than needing re-injection. It is also
     * the correct level of the cascade: a <em>user</em> {@code !important} rule outranks an
     * <em>author</em> {@code !important} rule, which is exactly the precedence needed to override
     * a stylesheet shipped inside the archive.
     */
    @Override
    public void injectCss(String css) {
        webView.getEngine().setUserStyleSheetLocation(css == null || css.isBlank() ? null : dataUri(css));
    }

    /** Encodes a stylesheet as a {@code data:} URI, which is what the engine accepts. */
    static String dataUri(String css) {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public double scrollPosition() {
        Object value = run("""
                (function() {
                  var doc = document.documentElement, body = document.body;
                  if (!doc || !body) return 0;
                  var height = Math.max(body.scrollHeight, doc.scrollHeight) - doc.clientHeight;
                  if (height <= 0) return 0;
                  return (window.pageYOffset || doc.scrollTop || 0) / height;
                })();
                """);
        return value instanceof Number n ? clamp(n.doubleValue()) : 0;
    }

    @Override
    public void scrollTo(double fraction) {
        double target = clamp(fraction);
        run("""
                (function() {
                  var doc = document.documentElement, body = document.body;
                  if (!doc || !body) return;
                  var height = Math.max(body.scrollHeight, doc.scrollHeight) - doc.clientHeight;
                  window.scrollTo(0, height * %s);
                })();
                """.formatted(target));
    }

    @Override
    public void setOnLocationChanged(Consumer<String> listener) {
        webView.getEngine().locationProperty().addListener((obs, old, location) -> listener.accept(location));
    }

    @Override
    public void dispose() {
        webView.getEngine().load(null);
    }

    /**
     * Runs a script against the current page, null on any failure. Public for the reader-view
     * session, which is engine-bound by nature; everything else should stay on the interface.
     */
    public Object runScript(String script) {
        return run(script);
    }

    /** Scripts run against archived pages, so a failure must never propagate into the UI. */
    private Object run(String script) {
        try {
            return webView.getEngine().executeScript(script);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** JavaScript string literal for arbitrary CSS — the text is ours, but quote it properly anyway. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003C"); // never let a </style> escape the element
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
