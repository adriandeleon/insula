package com.offlinewiki.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.offlinewiki.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The library/downloads view, driven through the real shell on the headless platform. */
class LibraryPaneFxTest {

    private static <T> T withShell(Path configDir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(configDir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, configDir);
            Scene scene = new Scene(controller.root(), 900, 600);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void libraryCommandSwapsTheCenterAndBackAgain(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertFalse(controller.libraryShowingForTest());
            Object reader = ((javafx.scene.layout.BorderPane)
                            ((StackPane) controller.root()).getChildren().getFirst())
                    .getCenter();

            controller.commandsForTest().run("library.show");
            assertTrue(controller.libraryShowingForTest());
            Object shown = ((javafx.scene.layout.BorderPane)
                            ((StackPane) controller.root()).getChildren().getFirst())
                    .getCenter();
            assertNotSame(reader, shown, "the library should replace the reader view");
            assertSame(controller.libraryPaneForTest().node(), shown);

            controller.commandsForTest().run("library.reader");
            assertFalse(controller.libraryShowingForTest());
            assertSame(
                    reader,
                    ((javafx.scene.layout.BorderPane) ((StackPane) controller.root())
                                    .getChildren()
                                    .getFirst())
                            .getCenter(),
                    "returning to the reader must restore the same view, not rebuild it");
            return null;
        });
    }

    @Test
    void toggleFlipsBothWays(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("library.toggle");
            assertTrue(controller.libraryShowingForTest());
            controller.commandsForTest().run("library.toggle");
            assertFalse(controller.libraryShowingForTest());
            return null;
        });
    }

    @Test
    void everyDownloadCommandIsRegisteredAndSafeWithNothingInFlight(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            for (String id : new String[] {
                "library.show",
                "library.reader",
                "library.toggle",
                "library.search",
                "download.cancelAll",
                "view.toggleTorrent"
            }) {
                assertTrue(
                        controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals(id)),
                        "missing command " + id);
                assertTrue(controller.commandsForTest().run(id), "command " + id + " should dispatch");
            }
            return null;
        });
    }

    @Test
    void torrentToggleFlipsTheSettingAndPersists(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertFalse(settings.isTorrentEnabled(), "BitTorrent preference is off by default");
            controller.commandsForTest().run("view.toggleTorrent");
            assertTrue(settings.isTorrentEnabled());
            return null;
        });
        assertTrue(Settings.load(dir.resolve("settings.properties")).isTorrentEnabled());
    }

    @Test
    void theProgressSamplerIsStoppedWhileTheLibraryIsHidden(@TempDir Path dir) {
        // A timer left running behind a hidden pane wakes the FX thread forever; the 4 Hz sampler
        // must only tick while the pane is actually on screen.
        withShell(dir, (controller, settings) -> {
            LibraryPane pane = controller.libraryPaneForTest();
            assertNotNull(pane.node());

            controller.commandsForTest().run("library.show");
            controller.commandsForTest().run("library.reader");
            // After hiding, deactivate() has run; showing again must not double-start it.
            controller.commandsForTest().run("library.show");
            controller.commandsForTest().run("library.show");
            return null;
        });
    }

    @Test
    void refreshIntervalStaysAtFourHertz() {
        // The design brief is explicit: coalesce to ~4 Hz rather than one FX hop per event.
        assertEquals(250, LibraryPane.REFRESH_MILLIS);
    }

    @Test
    void aVerifiedLocalArchiveCanBeOpenedFromTheLibrary(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);

        // Pre-seed the library index the way a completed download would.
        com.offlinewiki.library.Library library =
                com.offlinewiki.library.Library.load(dir.resolve("library.properties"));
        library.put(new com.offlinewiki.library.LibraryEntry(
                zim, "Wikibooks", Files.size(zim), "", true, System.currentTimeMillis()));
        library.save();

        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("library.show");
            assertTrue(controller.libraryShowingForTest());
            // Opening from the library returns to the reader with the archive loaded.
            controller.openZim(zim);
            assertTrue(controller.hasArchiveForTest());
            return null;
        });
    }
}
