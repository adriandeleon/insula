package com.insula.app;

/**
 * What the window shows, once Zen mode has had its say.
 *
 * <p>Zen strips the <b>window</b> — the menubar, the toolbar, the tab strip, the sidebar — and
 * leaves the <b>reading surface</b> alone. The article's own navigation row (back/forward, find,
 * text size, Reader View, the bookmark star) and the status line both stay, deliberately: they are
 * about what is being read rather than about the app talking about itself, and a reader who cannot
 * go back or resize the text without leaving the mode leaves the mode constantly. Anything added
 * here later has to answer the same question — is this the window, or the article?
 *
 * <p>Zen is an <b>effective overlay, not an edit</b>. Every rule is "the preference AND not hidden
 * by Zen", so entering the mode never writes to the preferences it appears to change and leaving it
 * restores exactly what the user had. The alternative — turning the sidebar off on the way in and
 * back on on the way out — loses the setting of anyone who was already reading without a sidebar,
 * and loses it silently.
 *
 * <p>It is a class of its own, and pure, because this is a truth table. Written inline it would be
 * four conditions spread across the apply methods that read them, which is where a mode desyncs:
 * one surface forgotten on the way back out, showing to nobody until someone notices the gap.
 *
 * <p>{@link ReaderController} evaluates the live flags and does the JavaFX; nothing here imports it.
 */
final class Chrome {

    private Chrome() {}

    /**
     * The menubar and the toolbar, which ride together in one box at the top of the window. Zen
     * wants neither: the surface switcher, the omnibox and the palette button are the window
     * talking about itself, which is the whole of what the mode is for getting rid of.
     */
    static boolean topChrome(boolean zen) {
        return !zen;
    }

    /**
     * The reader sidebar — Search, Bookmarks, History.
     *
     * <p>This is the one with a preference behind it ({@code Ctrl+\}), and so the one that shows why
     * the overlay shape matters: a reader who had the sidebar hidden already must still have it
     * hidden when they leave Zen.
     */
    static boolean sidebar(boolean sidebarVisible, boolean zen) {
        return sidebarVisible && !zen;
    }

    /**
     * The tab strip. Hidden by Zen even with several tabs open — they are still there, still
     * reachable by {@code Ctrl+Tab}, just not drawn.
     */
    static boolean tabStrip(boolean zen) {
        return !zen;
    }

    /** The floating way out. Only in Zen, since only Zen hides everything else that could be. */
    static boolean zenExit(boolean zen) {
        return zen;
    }
}
