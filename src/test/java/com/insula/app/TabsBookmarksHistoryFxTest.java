package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.reader.ArticleRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tabs, bookmarks and history driven through the real shell against a fixture archive. */
class TabsBookmarksHistoryFxTest {

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        Scene scene = new Scene(controller.root(), 1000, 700);
        controller.installShortcuts(scene);
        stage.setScene(scene);
        return controller;
    }

    private static Path fixture(Path dir) throws Exception {
        Path zim = dir.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        return zim;
    }

    /** Waits for the document to finish loading, not merely for the location to be set. */
    private static void awaitArticle(ReaderController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            boolean ready = FxTestSupport.callOnFx(() -> {
                String location = controller.rendererForTest().engine().getLocation();
                return location != null
                        && location.contains("/zim/")
                        && controller.rendererForTest().engine().getLoadWorker().getState()
                                == javafx.concurrent.Worker.State.SUCCEEDED;
            });
            if (ready) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the archive never finished loading an article");
    }

    @Test
    void openingAnArchiveGivesItATabAndVisitingRecordsHistory(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);

            assertEquals(
                    1, FxTestSupport.callOnFx(() -> controller.tabsForTest().count()));
            assertNotNull(FxTestSupport.callOnFx(
                    () -> controller.tabsForTest().active().article()));
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(() -> controller.historyForTest().size()),
                    "the article just read is in history");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void bookmarkingTogglesAndSurvivesARestart(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir);
        ArticleRef saved;
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("bookmark.toggle"));
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(() -> controller.bookmarksForTest().size()));
            saved = FxTestSupport.callOnFx(
                    () -> controller.bookmarksForTest().entries().getFirst());
            assertTrue(saved.title() != null && !saved.title().isBlank(), "a bookmark shows a real title");
            assertEquals("Wikibooks", saved.bookTitle(), "and names the book it came from");

            // Toggling again removes it.
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("bookmark.toggle"));
            assertEquals(
                    0,
                    FxTestSupport.callOnFx(() -> controller.bookmarksForTest().size()));

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("bookmark.toggle"));
        } finally {
            FxTestSupport.runOnFx(controller::dispose); // dispose persists the stores
        }

        // A fresh shell over the same config dir must see the bookmark and the history.
        ReaderController restarted = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            assertEquals(
                    1, FxTestSupport.callOnFx(() -> restarted.bookmarksForTest().size()));
            assertEquals(
                    saved.key(),
                    FxTestSupport.callOnFx(() ->
                            restarted.bookmarksForTest().entries().getFirst().key()));
            assertTrue(FxTestSupport.callOnFx(() -> restarted.historyForTest().size()) >= 1, "history persists too");
        } finally {
            FxTestSupport.runOnFx(restarted::dispose);
        }
    }

    @Test
    void tabsOpenCycleAndCloseThroughCommands(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("tab.new"));
            assertEquals(
                    2, FxTestSupport.callOnFx(() -> controller.tabsForTest().count()));

            int before = FxTestSupport.callOnFx(() -> controller.tabsForTest().activeIndex());
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("tab.next"));
            assertEquals(
                    before == 0 ? 1 : 0,
                    FxTestSupport.callOnFx(() -> controller.tabsForTest().activeIndex()),
                    "cycling wraps around two tabs");

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("tab.close"));
            assertEquals(
                    1, FxTestSupport.callOnFx(() -> controller.tabsForTest().count()));

            // Closing the last tab leaves the reader with nothing and falls back to the library.
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("tab.close"));
            assertEquals(
                    0, FxTestSupport.callOnFx(() -> controller.tabsForTest().count()));
            assertEquals(
                    ReaderController.Surface.LIBRARY,
                    FxTestSupport.callOnFx(controller::surfaceForTest),
                    "an empty reader must not be a blank screen");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void theSidebarSwitchesBetweenSearchBookmarksAndHistory(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);
            assertEquals(0, FxTestSupport.callOnFx(controller::sidebarPaneForTest), "search by default");

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("bookmark.show"));
            assertEquals(1, FxTestSupport.callOnFx(controller::sidebarPaneForTest));

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("history.show"));
            assertEquals(2, FxTestSupport.callOnFx(controller::sidebarPaneForTest));

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("search.show"));
            assertEquals(0, FxTestSupport.callOnFx(controller::sidebarPaneForTest));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void clearingHistoryEmptiesItAndPersists(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);
            assertTrue(FxTestSupport.callOnFx(() -> controller.historyForTest().size()) > 0);

            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("history.clear"));
            assertEquals(
                    0, FxTestSupport.callOnFx(() -> controller.historyForTest().size()));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
        assertFalse(
                Files.readString(dir.resolve("history.properties")).contains("nons-wikibooks"),
                "a cleared history must not come back from disk");
    }

    @Test
    void everyNewCommandIsRegisteredAndDispatches(@TempDir Path dir) {
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            for (String id : new String[] {
                "tab.new",
                "tab.close",
                "tab.next",
                "tab.previous",
                "bookmark.toggle",
                "bookmark.show",
                "history.show",
                "history.clear",
                "search.show"
            }) {
                assertTrue(
                        FxTestSupport.callOnFx(() ->
                                controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals(id))),
                        "missing command " + id);
                assertTrue(
                        FxTestSupport.callOnFx(
                                () -> controller.commandsForTest().run(id)),
                        id + " should dispatch with no archive open");
            }
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
