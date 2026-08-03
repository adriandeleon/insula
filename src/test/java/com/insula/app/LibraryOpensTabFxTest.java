package com.insula.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opening an archive from the Library puts it in its own tab. */
class LibraryOpensTabFxTest {

    private static final Path FIRST = Path.of("src/test/resources/zim/nons-wikibooks.zim");
    private static final Path SECOND = Path.of("src/test/resources/zim/withns-wikibooks.zim");

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1100, 700);
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
    void asecondArchiveGetsItsOwnTabRatherThanReplacingTheFirst(@TempDir Path dir) {
        // The reader holds one archive at a time, and loading the new front page used to rewrite
        // the active tab underneath the reader — the article being read simply vanished.
        withShell(dir, (controller, scene) -> {
            controller.openFromLibraryForTest(FIRST);
            assertEquals(1, controller.tabCountForTest());

            controller.openFromLibraryForTest(SECOND);
            assertEquals(2, controller.tabCountForTest(), "the first archive keeps its tab");
            List<String> archives = controller.tabArchivesForTest();
            assertTrue(archives.contains("nons-wikibooks.zim"), archives.toString());
            assertTrue(archives.contains("withns-wikibooks.zim"), archives.toString());
            return null;
        });
    }

    @Test
    void theNewTabIsTheOneInFront(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.openFromLibraryForTest(FIRST);
            controller.openFromLibraryForTest(SECOND);
            assertEquals(
                    "withns-wikibooks.zim", controller.tabArchivesForTest().get(controller.activeTabIndexForTest()));
            return null;
        });
    }

    @Test
    void askingForAnArchiveAlreadyOpenSelectsItInsteadOfOpeningItTwice(@TempDir Path dir) {
        // Two tabs on the same archive, both at its front page, is not what a second click means.
        withShell(dir, (controller, scene) -> {
            controller.openFromLibraryForTest(FIRST);
            controller.openFromLibraryForTest(SECOND);
            controller.openFromLibraryForTest(FIRST);
            assertEquals(2, controller.tabCountForTest());
            assertEquals(
                    "nons-wikibooks.zim",
                    controller.tabArchivesForTest().get(controller.activeTabIndexForTest()),
                    "and it is brought to the front");
            return null;
        });
    }
}
