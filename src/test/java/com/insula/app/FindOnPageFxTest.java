package com.insula.app;

import java.nio.file.Path;
import java.util.function.BiFunction;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Find on the current page: the bar's own behaviour, driven through the real controller. */
class FindOnPageFxTest {

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1100, 700);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            controller.openZim(Path.of("src/test/resources/zim/nons-wikibooks.zim"));
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            try {
                return body.apply(controller, scene);
            } finally {
                controller.dispose();
            }
        });
    }

    private static TextField findField(Scene scene) {
        return (TextField) scene.getRoot().lookupAll(".text-field").stream()
                .filter(n -> n instanceof TextField t && "Find on page".equals(t.getPromptText()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theBarStaysOutOfTheWayUntilAskedFor(@TempDir Path dir) {
        // It sits between the tabs and the article, and most reading does not involve searching.
        withShell(dir, (controller, scene) -> {
            // Asked of the bar, not the field inside it: a child Node's visible property is its
            // own, and stays true while a hidden parent keeps it off the screen.
            assertFalse(controller.findBarShowingForTest());
            controller.commandsForTest().run("find.onPage");
            assertTrue(controller.findBarShowingForTest());
            // Focus is not asserted here: it is requested again on the next pulse, which this
            // harness's synchronous body has not reached. openFind asks twice for that reason.
            return null;
        });
    }

    @Test
    void closingItPutsItAway(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("find.onPage");
            controller.closeFindForTest();
            assertFalse(controller.findBarShowingForTest(), "a page left marked up looks broken");
            return null;
        });
    }

    @Test
    void findingSomethingThatIsNotThereSaysSo(@TempDir Path dir) {
        // Silence would read as "still searching"; the count is the only feedback there is.
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("find.onPage");
            findField(scene).setText("zzzznotinthisarchivezzzz");
            assertEquals("no matches", controller.findCountForTest());
            return null;
        });
    }

    @Test
    void anEmptyQueryReportsNothingRatherThanNoMatches(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("find.onPage");
            findField(scene).setText("something");
            findField(scene).setText("");
            assertEquals("", controller.findCountForTest());
            return null;
        });
    }
}
