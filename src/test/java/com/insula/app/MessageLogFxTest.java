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

/** The status line records what it announces, and the popup opens and closes from it. */
class MessageLogFxTest {

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1000, 700);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, scene);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void everythingAnnouncedIsKept(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.setStatus("Moved 6 archives");
            controller.setStatus("Could not reach the catalog");
            assertEquals(
                    java.util.List.of("Could not reach the catalog", "Moved 6 archives"),
                    controller.messageLogForTest().entries().stream()
                            .map(MessageLog.Entry::text)
                            .toList());
            return null;
        });
    }

    @Test
    void aRealFlowGoesThroughItRatherThanStraightToTheLabel(@TempDir Path dir) {
        // The point of routing every call site: a message set by ordinary app code is recorded
        // without that code knowing the log exists.
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("history.clear");
            assertFalse(controller.messageLogForTest().isEmpty(), "a command's status should be logged");
            return null;
        });
    }

    @Test
    void theSameTargetOpensAndClosesIt(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.setStatus("something");
            controller.commandsForTest().run("view.messages");
            assertTrue(controller.messageLogPopupForTest().isShowing());
            controller.commandsForTest().run("view.messages");
            assertFalse(controller.messageLogPopupForTest().isShowing());
            return null;
        });
    }
}
