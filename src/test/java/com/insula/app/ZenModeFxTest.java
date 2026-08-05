package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zen mode against the real shell.
 *
 * <p>{@link ChromeTest} covers the truth table; what can only be proven here is that the table is
 * actually wired to the nodes — a mode that hides five of six surfaces looks right in a screenshot
 * and is still broken.
 */
class ZenModeFxTest {

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

    /** A shell with something to read, which is the only state in which Zen means anything. */
    private static ReaderController reading(Path dir) throws Exception {
        Path zim = fixture(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        FxTestSupport.runOnFx(() -> controller.openZim(zim));
        awaitArticle(controller);
        return controller;
    }

    @Test
    void zenStripsTheWindowAndGivesItAllBack(@TempDir Path dir) throws Exception {
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                assertTrue(controller.topChromeForTest().isVisible());
                assertFalse(controller.zenExitForTest().isVisible(), "nothing to exit yet");

                controller.commandsForTest().run("view.toggleZen");

                assertTrue(controller.zenActiveForTest());
                assertFalse(controller.topChromeForTest().isVisible(), "menubar and toolbar");
                assertTrue(
                        controller.tabBarForTest().getStyleClass().contains("zen-tabs"),
                        "the tab strip has no node to hide, so it is collapsed by style class");
                assertEquals(
                        1,
                        controller.readerSplitForTest().getItems().size(),
                        "and the sidebar is removed rather than blanked");
                assertTrue(controller.zenExitForTest().isVisible(), "the clickable way out");

                // Hidden is not enough — an invisible-but-managed node still reserves its height,
                // which in Zen is a band of empty window where the toolbar used to be.
                assertFalse(controller.topChromeForTest().isManaged());

                controller.commandsForTest().run("view.toggleZen");

                assertFalse(controller.zenActiveForTest());
                assertTrue(controller.topChromeForTest().isVisible());
                assertFalse(controller.tabBarForTest().getStyleClass().contains("zen-tabs"));
                assertEquals(2, controller.readerSplitForTest().getItems().size());
                assertFalse(controller.zenExitForTest().isVisible());
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void zenKeepsTheReadingSurfaceItself(@TempDir Path dir) throws Exception {
        // The line between window and article. The row below the tabs carries back/forward, find,
        // text size, Reader View and the bookmark star — all of them about what is being read — and
        // a reader who has to leave Zen to go back leaves Zen constantly. The status line reports
        // on the article and its downloads, and takes a single row to do it.
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("view.toggleZen");
                assertTrue(controller.zenActiveForTest());

                assertTrue(controller.navRowForTest().isVisible(), "the row below the tabs stays");
                assertTrue(controller.navRowForTest().isManaged());
                assertTrue(controller.statusBarForTest().isVisible(), "and so does the status line");
                assertTrue(controller.statusBarForTest().isManaged());
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void leavingZenGivesBackTheSidebarTheReaderActuallyHad(@TempDir Path dir) throws Exception {
        // The reason Zen gates the preference instead of writing to it: someone reading without a
        // sidebar must not be handed one on the way out.
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("view.toggleSidebar");
                assertFalse(controller.settingsForTest().isSidebarVisible());

                controller.commandsForTest().run("view.toggleZen");
                controller.commandsForTest().run("view.toggleZen");

                assertFalse(
                        controller.settingsForTest().isSidebarVisible(),
                        "Zen must not have written to the sidebar preference");
                assertEquals(1, controller.readerSplitForTest().getItems().size());
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void escapeLeavesZen(@TempDir Path dir) throws Exception {
        // The gesture a reader reaches for before finding a shortcut, and the reason the mode is
        // safe to persist.
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("view.toggleZen");
                assertTrue(controller.zenActiveForTest());

                controller.root().getScene().getRoot().fireEvent(escape());
                assertFalse(controller.zenActiveForTest());
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void escapeIsLeftAloneWhenTheFindBarOwnsIt(@TempDir Path dir) throws Exception {
        // Zen must not break two working controls: the find bar closes on Escape, and gets it.
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("view.toggleZen");
                controller.commandsForTest().run("find.onPage");
                assertTrue(controller.findBarShowingForTest());

                controller.root().getScene().getRoot().fireEvent(escape());
                assertTrue(controller.zenActiveForTest(), "Escape belonged to the find bar");
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void zenIsRefusedWithNothingToRead(@TempDir Path dir) {
        // Stripping the window down to an empty Library is worse than not entering at all.
        FxTestSupport.runOnFx(() -> {
            ReaderController controller = shell(dir);
            try {
                controller.commandsForTest().run("view.toggleZen");

                assertFalse(controller.settingsForTest().isZenMode());
                assertTrue(controller.topChromeForTest().isVisible());
                assertTrue(controller.statusTextForTest().contains("Nothing open"));
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void zenStandsDownAwayFromTheReader(@TempDir Path dir) throws Exception {
        // A stripped Library has no switcher and no way back to the article.
        ReaderController controller = reading(dir);
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("view.toggleZen");
                assertFalse(controller.topChromeForTest().isVisible());

                controller.commandsForTest().run("library.open");
                assertFalse(controller.zenActiveForTest());
                assertTrue(controller.topChromeForTest().isVisible(), "the Library keeps its chrome");

                controller.commandsForTest().run("library.reader");
                assertTrue(controller.zenActiveForTest(), "and the mode resumes on the way back");
                assertFalse(controller.topChromeForTest().isVisible());
            });
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    private static KeyEvent escape() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
    }
}
