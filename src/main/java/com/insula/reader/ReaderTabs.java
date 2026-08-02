package com.insula.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * The open tabs and which one is showing — pure state, so the awkward parts (closing the active
 * tab, closing the last one, bounds) are decided by unit tests rather than by clicking.
 *
 * <p>Tabs share a <b>single</b> renderer. One WebView per tab would hold an engine, a scene graph
 * and GPU textures per open article, which is the memory shape this project deliberately avoids
 * everywhere else; loads out of a local archive are sub-second, so a tab switch reloads and
 * restores the scroll it recorded. The cost is that in-page state (a playing video, an entered
 * Reader View) belongs to the tab that is showing, which is why {@link Tab} carries only what can
 * be restored faithfully.
 */
public final class ReaderTabs {

    /** One open article. {@code scroll} is a fraction of document height, as positions are. */
    public static final class Tab {
        private ArticleRef article;
        private double scroll;

        Tab(ArticleRef article) {
            this.article = article;
        }

        public ArticleRef article() {
            return article;
        }

        public void setArticle(ArticleRef article) {
            this.article = article;
        }

        public double scroll() {
            return scroll;
        }

        public void setScroll(double scroll) {
            this.scroll = Math.max(0, Math.min(1, scroll));
        }

        /** What the tab strip shows. */
        public String label() {
            return article == null ? "New tab" : article.title();
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int activeIndex = -1;

    public List<Tab> tabs() {
        return List.copyOf(tabs);
    }

    public int count() {
        return tabs.size();
    }

    public int activeIndex() {
        return activeIndex;
    }

    public Tab active() {
        return activeIndex >= 0 && activeIndex < tabs.size() ? tabs.get(activeIndex) : null;
    }

    public Tab at(int index) {
        return index >= 0 && index < tabs.size() ? tabs.get(index) : null;
    }

    /** Opens a tab after the active one — a link opened in a new tab belongs next to its source. */
    public Tab open(ArticleRef article, boolean activate) {
        Tab tab = new Tab(article);
        int insertAt = activeIndex < 0 ? tabs.size() : activeIndex + 1;
        tabs.add(insertAt, tab);
        if (activate || activeIndex < 0) {
            activeIndex = insertAt;
        } else if (insertAt <= activeIndex) {
            activeIndex++;
        }
        return tab;
    }

    public void select(int index) {
        if (index >= 0 && index < tabs.size()) {
            activeIndex = index;
        }
    }

    public boolean selectTab(Tab tab) {
        int index = tabs.indexOf(tab);
        if (index < 0) {
            return false;
        }
        activeIndex = index;
        return true;
    }

    /**
     * Closes a tab and returns the tab that should now show, or null when none is left.
     *
     * <p>Closing the active tab activates the one to its <b>right</b>, falling back to the left at
     * the end of the strip — the browser convention, and the one that keeps a "close, close,
     * close" run moving forward through the tabs rather than backwards through the ones already
     * dealt with.
     */
    public Tab close(int index) {
        if (index < 0 || index >= tabs.size()) {
            return active();
        }
        tabs.remove(index);
        if (tabs.isEmpty()) {
            activeIndex = -1;
            return null;
        }
        if (index < activeIndex) {
            activeIndex--;
        } else if (index == activeIndex) {
            activeIndex = Math.min(index, tabs.size() - 1);
        }
        return active();
    }

    public Tab closeActive() {
        return close(activeIndex);
    }

    /** Moves to the next tab, wrapping. Wrapping is what makes Ctrl+Tab a cycle rather than a wall. */
    public Tab next() {
        if (tabs.isEmpty()) {
            return null;
        }
        activeIndex = (activeIndex + 1) % tabs.size();
        return active();
    }

    public Tab previous() {
        if (tabs.isEmpty()) {
            return null;
        }
        activeIndex = (activeIndex - 1 + tabs.size()) % tabs.size();
        return active();
    }
}
