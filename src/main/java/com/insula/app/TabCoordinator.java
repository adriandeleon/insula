package com.insula.app;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import com.insula.reader.ArticleRef;
import com.insula.reader.ReaderSession;
import com.insula.reader.ReaderTabs;

/**
 * The reader's tabs: the model, the strip that follows it, and the session that outlives both.
 *
 * <p>Split out of {@link ReaderController}, which had grown to three and a half thousand lines and
 * carried this alongside everything else. Tabs are a good first thing to lift out because they are
 * genuinely self-contained — the model decides, the strip follows — and because most of what has
 * gone wrong here recently was a question of ordering between the two.
 *
 * <p>What it cannot do itself it asks {@link Ops} for. Loading an article means opening an archive
 * and pointing the renderer at a URL, which is the window's business; the coordinator's business
 * is knowing <em>which</em> article, and when.
 */
final class TabCoordinator {

    /** What the coordinator needs from the window around it. */
    interface Ops {

        /**
         * Opens the archive if it is not the one already open, and loads the article.
         *
         * @return false when the archive could not be opened, so the caller does not go on to
         *     restore a scroll position into a page that never arrived
         */
        boolean loadArticle(ArticleRef ref);

        /** Where the reader is scrolled to now, 0–1. */
        double scrollPosition();

        void scrollTo(double fraction);

        /** Whether the reader is the surface on screen — a scroll read off a hidden one is noise. */
        boolean readerShowing();

        /** Called when the last tab closes: there is nothing left to read. */
        void showLibrary();

        /** The article the reader is on, or null. */
        ArticleRef currentArticle();

        /** The open archive's front page, or null when nothing is open. */
        ArticleRef mainPage();

        void setStatus(String message);
    }

    /** How many closed tabs can be reopened. Beyond this it is archaeology, not undo. */
    private static final int MAX_CLOSED_TABS = 20;

    /** How long to let a page settle before restoring its scroll. */
    private static final Duration SCROLL_SETTLE = Duration.millis(200);

    private final ReaderTabs tabs = new ReaderTabs();
    private final TabPane tabBar = new TabPane();
    /** The UI tab a model tab is currently drawn as. Identity, because tabs carry no id. */
    private final java.util.Map<javafx.scene.control.Tab, ReaderTabs.Tab> tabModel = new java.util.IdentityHashMap<>();

    private final Deque<ArticleRef> closedTabs = new ArrayDeque<>();
    private final ReaderSession session;
    private final Ops ops;

    /** The single renderer node, moved into whichever tab is showing. */
    private javafx.scene.Node tabContent;

    /** True while the strip is being rebuilt, so its own events are not mistaken for the user's. */
    private boolean syncingTabs;

    TabCoordinator(ReaderSession session, Ops ops) {
        this.session = session;
        this.ops = ops;
        tabBar.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (!syncingTabs && selected != null) {
                onTabSelected(selected);
            }
        });
        tabBar.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> {
            while (change.next()) {
                change.getRemoved().forEach(this::onTabClosedByUser);
            }
        });
    }

    TabPane strip() {
        return tabBar;
    }

    ReaderTabs model() {
        return tabs;
    }

    /** The renderer node the tabs share; set once the window has built it. */
    void setTabContent(javafx.scene.Node content) {
        this.tabContent = content;
    }

    int count() {
        return tabs.count();
    }

    int closedCount() {
        return closedTabs.size();
    }

    // ---- opening ----------------------------------------------------------------------

    void openTabFor(ArticleRef ref) {
        ReaderTabs.Tab tab = tabs.open(ref, true);
        syncStrip();
        activate(tab);
    }

    /**
     * A new tab starts at the archive's main page, not at a copy of the current article — with no
     * address bar, the front of the book is the only meaningful fresh start.
     */
    void newTab() {
        ArticleRef ref = ops.mainPage();
        if (ref == null) {
            ops.showLibrary(); // nothing to put in a tab yet; the library is where one gets filled
            return;
        }
        openTabFor(ref);
    }

    /** The tab showing this archive, or -1. */
    int indexOfArchive(java.nio.file.Path archiveFile) {
        java.nio.file.Path target = archiveFile.toAbsolutePath();
        java.util.List<ReaderTabs.Tab> open = tabs.tabs();
        for (int i = 0; i < open.size(); i++) {
            ArticleRef ref = open.get(i).article();
            if (ref != null && target.equals(ref.archiveFile().toAbsolutePath())) {
                return i;
            }
        }
        return -1;
    }

    /** Brings an already-open tab to the front, as a click on the strip would. */
    void select(int index) {
        ReaderTabs.Tab tab = tabs.at(index);
        if (tab == null || tab == tabs.active()) {
            return;
        }
        rememberScroll();
        tabs.select(index);
        syncStrip();
        activate(tab);
    }

    // ---- closing ----------------------------------------------------------------------

    void closeActive() {
        if (tabs.count() == 0) {
            return;
        }
        ReaderTabs.Tab closing = tabs.active();
        if (closing != null) {
            rememberClosed(closing.article());
        }
        afterClose(tabs.closeActive());
    }

    void reopenClosed() {
        if (closedTabs.isEmpty()) {
            ops.setStatus("No recently closed tabs");
            return;
        }
        openTabFor(closedTabs.pop());
    }

    private void rememberClosed(ArticleRef ref) {
        if (ref == null) {
            return;
        }
        closedTabs.push(ref);
        while (closedTabs.size() > MAX_CLOSED_TABS) {
            closedTabs.removeLast();
        }
    }

    void cycle(int delta) {
        ReaderTabs.Tab tab = delta > 0 ? tabs.next() : tabs.previous();
        if (tab != null) {
            syncStrip();
            activate(tab);
        }
    }

    // ---- the strip --------------------------------------------------------------------

    /** Rebuilds the strip from the model. The guard stops the rebuild firing selection events. */
    void syncStrip() {
        syncingTabs = true;
        try {
            tabModel.clear();
            tabBar.getTabs().clear();
            for (ReaderTabs.Tab tab : tabs.tabs()) {
                javafx.scene.control.Tab uiTab = new javafx.scene.control.Tab();
                // The label lives in the graphic so an archive chip can sit beside it; the text is
                // left empty rather than duplicated, which would render the title twice.
                Label label = new Label(tab.label());
                String chip = tabs.chipFor(tab);
                if (chip.isBlank()) {
                    uiTab.setGraphic(label);
                } else {
                    Label chipLabel = new Label(chip);
                    chipLabel.getStyleClass().addAll("pill", "pill-neutral");
                    uiTab.setGraphic(new HBox(6, label, chipLabel));
                }
                tabModel.put(uiTab, tab);
                tabBar.getTabs().add(uiTab);
            }
            if (tabs.activeIndex() >= 0 && tabs.activeIndex() < tabBar.getTabs().size()) {
                javafx.scene.control.Tab selected = tabBar.getTabs().get(tabs.activeIndex());
                selected.setContent(tabContent);
                tabBar.getSelectionModel().select(selected);
            }
        } finally {
            syncingTabs = false;
        }
        rememberSession();
    }

    private void onTabSelected(javafx.scene.control.Tab uiTab) {
        ReaderTabs.Tab tab = tabModel.get(uiTab);
        if (tab == null || tab == tabs.active()) {
            return;
        }
        rememberScroll();
        tabs.selectTab(tab);
        moveRendererInto(uiTab);
        activate(tab);
    }

    private void onTabClosedByUser(javafx.scene.control.Tab uiTab) {
        if (syncingTabs) {
            return;
        }
        ReaderTabs.Tab tab = tabModel.remove(uiTab);
        if (tab == null) {
            return;
        }
        rememberClosed(tab.article());
        afterClose(tabs.close(tabs.tabs().indexOf(tab)));
    }

    /** What follows any close: the strip catches up, and something has to be on screen. */
    private void afterClose(ReaderTabs.Tab next) {
        syncStrip();
        if (next == null) {
            ops.showLibrary();
        } else {
            activate(next);
        }
    }

    /** All tabs share one engine, so the renderer node moves to whichever tab is showing. */
    private void moveRendererInto(javafx.scene.control.Tab uiTab) {
        for (javafx.scene.control.Tab other : tabBar.getTabs()) {
            if (other != uiTab && other.getContent() == tabContent) {
                other.setContent(null);
            }
        }
        uiTab.setContent(tabContent);
    }

    /** Loads the tab's article and restores the scroll it recorded. */
    void activate(ReaderTabs.Tab tab) {
        if (tab == null || tab.article() == null || !ops.loadArticle(tab.article())) {
            return;
        }
        double scroll = tab.scroll();
        if (scroll > 0) {
            PauseTransition settle = new PauseTransition(SCROLL_SETTLE);
            settle.setOnFinished(e -> ops.scrollTo(scroll));
            settle.play();
        }
    }

    /**
     * Following a link inside a tab changes what that tab holds; without this the strip would keep
     * showing the title the tab was opened with and a switch back would reload the wrong article.
     */
    void syncActiveToCurrentArticle() {
        ArticleRef ref = ops.currentArticle();
        if (ref == null) {
            return;
        }
        ReaderTabs.Tab active = tabs.active();
        if (active == null) {
            tabs.open(ref, true);
            syncStrip();
            return;
        }
        active.setArticle(ref);
        active.setScroll(0);
        // The title moved, so the strip is rebuilt rather than poked: a new article can change
        // whether chips are warranted at all.
        syncStrip();
    }

    void rememberScroll() {
        ReaderTabs.Tab active = tabs.active();
        if (active != null && ops.readerShowing()) {
            active.setScroll(ops.scrollPosition());
        }
    }

    /**
     * Records the open set so a restart can reopen it. Called wherever the strip changes rather
     * than only at shutdown, because a crash or a kill is exactly when losing the session stings.
     */
    void rememberSession() {
        session.set(
                tabs.tabs().stream()
                        .map(ReaderTabs.Tab::article)
                        .filter(Objects::nonNull)
                        .toList(),
                tabs.activeIndex());
        session.save();
    }

    /**
     * Reopens a saved session.
     *
     * <p>Opened in order so the strip matches last time, then the remembered one is selected —
     * opening the active tab last would reorder the strip instead.
     */
    void restore(ReaderSession.Restored restored, java.util.function.Consumer<ArticleRef> open) {
        restored.open().forEach(open);
        int active = restored.activeIndex();
        if (active >= 0 && active < tabs.count()) {
            ReaderTabs.Tab tab = tabs.tabs().get(active);
            tabs.selectTab(tab);
            syncStrip();
            activate(tab);
        }
        if (restored.dropped() > 0) {
            int kept = restored.open().size();
            ops.setStatus("Reopened " + kept + " tab" + (kept == 1 ? "" : "s") + " · " + restored.dropped()
                    + " skipped, their archives are no longer on this device");
        }
    }
}
