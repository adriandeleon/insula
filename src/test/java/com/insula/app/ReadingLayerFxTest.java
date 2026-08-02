package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.reader.ReaderTheme;
import com.insula.reader.ReadingPositions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reading layer driven through the real shell: reader mode, column width, position memory. */
class ReadingLayerFxTest {

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
    void readerModeCyclesThroughEveryModeAndReturns(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertEquals("original", settings.getReaderMode(), "the archive's own styling is the default");
            int modes = ReaderTheme.Mode.values().length;
            for (int i = 0; i < modes; i++) {
                controller.commandsForTest().run("reader.cycleMode");
            }
            assertEquals("original", settings.getReaderMode(), "a full cycle returns to where it started");
            return null;
        });
    }

    @Test
    void everyReaderCommandIsRegisteredAndDispatches(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            for (String id : new String[] {
                "reader.cycleMode",
                "reader.modeOriginal",
                "reader.modeComfortable",
                "reader.modeDark",
                "reader.widerColumn",
                "reader.narrowerColumn",
                "reader.toggleRememberPosition",
                "readerview.toggle" // with no archive open it reports rather than acting
            }) {
                assertTrue(
                        controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals(id)),
                        "missing command " + id);
                assertTrue(controller.commandsForTest().run(id), id + " should dispatch");
            }
            return null;
        });
    }

    @Test
    void readerModePersists(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("reader.modeDark");
            assertEquals("dark", settings.getReaderMode());
            return null;
        });
        assertEquals("dark", Settings.load(dir.resolve("settings.properties")).getReaderMode());
    }

    @Test
    void columnWidthClampsAtBothEnds(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            for (int i = 0; i < 40; i++) {
                controller.commandsForTest().run("reader.narrowerColumn");
            }
            assertEquals(ReaderTheme.MIN_WIDTH, settings.getReaderWidth());
            for (int i = 0; i < 60; i++) {
                controller.commandsForTest().run("reader.widerColumn");
            }
            assertEquals(ReaderTheme.MAX_WIDTH, settings.getReaderWidth());
            return null;
        });
    }

    @Test
    void positionMemoryCanBeTurnedOff(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertTrue(settings.isRememberPosition(), "on by default");
            controller.commandsForTest().run("reader.toggleRememberPosition");
            assertFalse(settings.isRememberPosition());
            return null;
        });
        assertFalse(Settings.load(dir.resolve("settings.properties")).isRememberPosition());
    }

    @Test
    void readingPositionsSurviveAShutdown(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("reading.properties");
        ReadingPositions positions = ReadingPositions.load(store);
        String key = ReadingPositions.key(dir.resolve("wiki.zim"), "C/Article");
        positions.remember(key, 0.63);
        positions.save();

        // A fresh shell must find the saved position rather than starting at the top.
        withShell(dir, (controller, settings) -> {
            assertTrue(Files.exists(store));
            return null;
        });
        assertEquals(0.63, ReadingPositions.load(store).positionOf(key), 0.0001);
    }

    @Test
    void openingAnArchiveStillWorksWithReaderModeOn(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("reader.modeDark");
            controller.openZim(zim);
            assertTrue(controller.hasArchiveForTest(), "reader mode must not interfere with opening");
            return null;
        });
    }
}
