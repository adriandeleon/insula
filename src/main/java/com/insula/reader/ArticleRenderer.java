package com.insula.reader;

import java.util.function.Consumer;

import javafx.scene.Node;

/**
 * The surface that displays an article.
 *
 * <p>This exists so the rendering engine is a replaceable part. JavaFX WebView is the shipped
 * implementation and measures well against real content (see {@code CLAUDE.md}), but it is
 * single-process: a WebKit crash on a pathological page takes the whole JVM down, mid-download.
 * Keeping the reading layer behind this interface means moving to an out-of-process engine such
 * as JCEF would be a new implementation rather than a rewrite of everything built on top.
 *
 * <p>Deliberately narrow — only what the reader actually needs. Anything that leaks WebView types
 * through here defeats the purpose.
 */
public interface ArticleRenderer {

    /** The node to place in the scene. */
    Node node();

    /** Loads an article by URL (served by the loopback ZIM server). */
    void load(String url);

    boolean canGoBack();

    boolean canGoForward();

    void goBack();

    void goForward();

    /** 1.0 is 100%. */
    void setZoom(double factor);

    /**
     * Applies stylesheet rules on top of whatever the archive shipped — the hook for reader mode,
     * a real dark theme and a readable content width. Kiwix's dark mode fights embedded
     * stylesheets and loses; overriding here is how that is won.
     */
    void injectCss(String css);

    /** Vertical scroll as a fraction of document height, 0.0–1.0, for remembering a position. */
    double scrollPosition();

    void scrollTo(double fraction);

    /** Called with every location the view navigates to, including in-page links. */
    void setOnLocationChanged(Consumer<String> listener);

    /** Releases the engine's resources. */
    void dispose();
}
