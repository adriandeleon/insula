package com.insula.reader;

import java.util.function.Function;

/**
 * Drives Reader View against a script runner — enter, restyle, and the navigation reset.
 *
 * <p>The runner is injected (in production {@code WebViewRenderer::runScript}) so the whole state
 * machine is testable with a fake that returns canned results, and separately against a real
 * WebView in the FX suite. The session itself holds only what the UI needs to ask: whether the
 * reader page is currently showing, and how long the article is.
 */
public final class ReaderViewSession {

    private final Function<String, Object> scripts;
    private boolean active;
    private int words = -1;

    public ReaderViewSession(Function<String, Object> scripts) {
        this.scripts = scripts;
    }

    /** Mozilla's "is this page probably readerable" check — gates the toolbar button. */
    public boolean probe() {
        return Boolean.TRUE.equals(scripts.apply(ReaderView.probeScript()));
    }

    /**
     * Extracts the article and swaps the document for the reader shell. False when the page has
     * no extractable article — the caller reports that instead of showing an empty husk.
     */
    public boolean enter(String sourceLabel, ReaderView.Prefs prefs) {
        Object count = scripts.apply(ReaderView.extractScript());
        words = count instanceof Number n ? n.intValue() : -1;
        if (words < 0) {
            return false;
        }
        Object swapped = scripts.apply(ReaderView.enterScript(sourceLabel, ReaderView.readingTime(words), prefs));
        active = Boolean.TRUE.equals(swapped);
        return active;
    }

    /** Live restyle from the Aa panel; a no-op unless the reader page is actually showing. */
    public void applyPrefs(ReaderView.Prefs prefs) {
        if (active) {
            scripts.apply(ReaderView.prefsScript(prefs));
        }
    }

    /**
     * The document changed under us — a link was followed or the page reloaded. Reader View is
     * per-article state, so it simply ends; there is nothing to undo because the old document is
     * gone.
     */
    public void pageChanged() {
        active = false;
        words = -1;
    }

    public boolean isActive() {
        return active;
    }

    public int words() {
        return words;
    }
}
