package com.insula.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.fulltext.FullTextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finding the feature, watching it run, and getting the disk back. */
class FullTextUiFxTest {

    private static final Path ZIM = Path.of("src/test/resources/zim/nons-wikibooks.zim");

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1200, 800);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            controller.openZim(ZIM);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            try {
                return body.apply(controller, scene);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void theProgressStripStaysOutOfTheWayUntilSomethingIsIndexing(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            HBox strip = controller.fullTextForTest().strip();
            assertFalse(strip.isVisible(), "nothing is indexing");
            assertFalse(strip.isManaged(), "and it takes no room in the status bar");
            return null;
        });
    }

    @Test
    void theStripCarriesBothQuestionsALongJobRaises(@TempDir Path dir) {
        // How far along is it, and how do I stop it. A quarter of an hour is too long for either
        // to be unanswerable at a glance.
        withShell(dir, (controller, scene) -> {
            HBox strip = controller.fullTextForTest().strip();
            assertTrue(
                    strip.getChildren().stream().anyMatch(n -> n instanceof javafx.scene.control.ProgressBar), "a bar");
            assertTrue(
                    strip.getChildren().stream()
                            .anyMatch(n -> n instanceof javafx.scene.control.Button b && "Stop".equals(b.getText())),
                    "and a way to stop");
            return null;
        });
    }

    @Test
    void anUnknownTotalReadsAsWorkingRatherThanStuck() {
        // A determinate bar at zero says "stuck", which is the opposite of what is happening.
        FullTextService.Progress unknown = new FullTextService.Progress("Wikibooks", 0, 0, 0);
        assertTrue(unknown.fraction() < 0);
        assertEquals("Indexing Wikibooks", FullTextCoordinator.describe(unknown));
    }

    @Test
    void theCaptionNamesTheArchiveAndHowFar() {
        // Which archive matters: the job outlives whatever the reader has moved on to.
        FullTextService.Progress half = new FullTextService.Progress("MDWiki", 50, 100, 40);
        assertEquals("Indexing MDWiki - 50%", FullTextCoordinator.describe(half));
    }

    @Test
    void theLibraryRowOffersToSearchInsideAndNotToBuildAnIndex(@TempDir Path dir) throws Exception {
        // Named for what somebody wants. Nobody wants an index; they want to find a sentence they
        // half remember.
        Path archive = dir.resolve("copy.zim");
        java.nio.file.Files.copy(ZIM, archive);
        withShell(dir, (controller, scene) -> {
            com.insula.library.Library library = controller.libraryForTest();
            library.put(new com.insula.library.LibraryEntry(archive, "Wikibooks", 10, "", true, 1));
            controller.commandsForTest().run("library.open");
            LibraryPane pane = controller.libraryPaneForTest();
            pane.activate();
            pane.node().applyCss();
            pane.node().layout();

            List<String> items = rowMenuLabels(pane.node());
            assertFalse(items.isEmpty(), "the row has a menu at all");
            assertTrue(items.stream().anyMatch(t -> t.startsWith("Search inside")), items.toString());
            assertFalse(items.stream().anyMatch(t -> t.contains("Build")), "not named after the machinery");
            return null;
        });
    }

    @Test
    void settingsSaysWhatTheIndexesCost(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            String usage = controller.indexUsageLabelForTest();
            assertTrue(usage.toLowerCase().contains("no text indexes"), usage);
            return null;
        });
    }

    /** Scoped to the pane: the window's own menubar is full of MenuButtons too. */
    private static List<String> rowMenuLabels(javafx.scene.Node paneNode) {
        return paneNode.lookupAll(".menu-button").stream()
                .filter(javafx.scene.control.MenuButton.class::isInstance)
                .map(javafx.scene.control.MenuButton.class::cast)
                .flatMap(m -> m.getItems().stream())
                .map(MenuItem::getText)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
