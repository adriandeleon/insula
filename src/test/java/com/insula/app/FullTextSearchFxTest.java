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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full-text search as the reader meets it: findable, opt-in, and never silently partial. */
class FullTextSearchFxTest {

    private static final Path ZIM = Path.of("src/test/resources/zim/nons-wikibooks.zim");

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1100, 700);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            controller.openZim(ZIM);
            try {
                return body.apply(controller, scene);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void anArchiveIsNotIndexedUntilItIsAskedFor(@TempDir Path dir) {
        // Indexing costs real time. Starting it because somebody opened a file would be the app
        // taking a decision it has no right to.
        withShell(dir, (controller, scene) -> {
            assertFalse(controller.fullTextForTest().isIndexed(ZIM.toAbsolutePath()));
            assertFalse(controller.fullTextForTest().isIndexing());
            return null;
        });
    }

    @Test
    void anUnindexedArchiveSaysSoRatherThanReturningLess(@TempDir Path dir) {
        // The alternative is a search that quietly finds fewer things than it should, which
        // nobody can tell apart from there being nothing to find.
        withShell(dir, (controller, scene) -> {
            String offer = controller.fullTextForTest().offerForCurrentArchive();
            assertFalse(offer.isBlank());
            assertTrue(offer.toLowerCase().contains("index"), offer);
            return null;
        });
    }

    @Test
    void askingForAnIndexStartsOneAndSaysSo(@TempDir Path dir) {
        // What the build then produces is FullTextServiceTest's job — it can wait for the result
        // properly, which a shell test holding the FX thread cannot.
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("search.buildIndex");
            String said = controller.messageLogForTest().entries().getFirst().text();
            assertTrue(said.contains("index"), said);
            return null;
        });
    }

    @Test
    void withNoArchiveOpenThereIsNothingToOfferAndNothingToIndex(@TempDir Path dir) {
        FxTestSupport.runOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(controller.root(), 1100, 700));
            try {
                assertTrue(controller.fullTextForTest().offerForCurrentArchive().isBlank());
                controller.commandsForTest().run("search.buildIndex");
                assertTrue(
                        controller
                                .messageLogForTest()
                                .entries()
                                .getFirst()
                                .text()
                                .contains("Open an archive"),
                        "it says what to do rather than failing quietly");
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void progressDoesNotFloodTheMessageLog(@TempDir Path dir) {
        // Progress changes several times a second. Announcing it would bury every real message
        // under a thousand rows of a percentage counting up.
        withShell(dir, (controller, scene) -> {
            controller.setStatus("something worth keeping");
            int before = controller.messageLogForTest().size();
            for (int i = 0; i < 50; i++) {
                controller.setProgressForTest("Indexing - " + i + "%");
            }
            assertEquals(before, controller.messageLogForTest().size(), "progress is shown, not announced");
            controller.setProgressForTest("");
            assertEquals(
                    "something worth keeping",
                    controller.statusTextForTest(),
                    "clearing progress restores what was last announced");
            return null;
        });
    }

    @Test
    void everyFullTextCommandIsReachable(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            List<String> ids = controller.commandsForTest().all().stream()
                    .map(com.insula.command.Command::id)
                    .toList();
            for (String id : List.of("search.buildIndex", "search.cancelIndex", "search.deleteIndex")) {
                assertTrue(ids.contains(id), id + " missing from " + ids.size() + " commands");
            }
            return null;
        });
    }
}
