package com.insula.app;

import java.nio.file.Path;
import java.util.function.BiFunction;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The manual offline switch: what it says, what it refuses, and that it is remembered. */
class OfflineModeFxTest {

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1000, 700);
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
    void theSwitchIsRememberedAcrossLaunches(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("network.toggleOffline");
            assertTrue(settings.isWorkOffline());
            return null;
        });
        assertTrue(Settings.load(dir.resolve("settings.properties")).isWorkOffline());
        withShell(dir, (controller, settings) -> {
            assertEquals("Offline", controller.networkLabelForTest());
            return null;
        });
    }

    @Test
    void goingOfflineRefusesTheCatalogAndSaysWhy(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("network.toggleOffline");
            controller.commandsForTest().run("catalog.refresh");
            String said = controller.messageLogForTest().entries().getFirst().text();
            assertTrue(said.contains("set to work offline"), said);
            // Not "no connection": the reader would go looking for a network problem they do not
            // have. The two offlines are different situations with different fixes.
            assertFalse(said.contains("No connection"), said);
            return null;
        });
    }

    @Test
    void comingBackOnlineSaysSo(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("network.toggleOffline");
            controller.commandsForTest().run("network.toggleOffline");
            assertFalse(settings.isWorkOffline());
            assertEquals(
                    "Back online",
                    controller.messageLogForTest().entries().getFirst().text());
            return null;
        });
    }
}
