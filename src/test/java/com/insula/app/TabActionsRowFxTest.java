package com.insula.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the article's own controls live, and what the article's right-click menu offers. */
class TabActionsRowFxTest {

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

    /** The row lives inside the showing tab, so it is only in the scene once something is open. */
    private static void openSomething(ReaderController controller, Scene scene) {
        controller.openZim(Path.of("src/test/resources/zim/nons-wikibooks.zim"));
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }

    private static List<String> buttonTextsIn(Node root, String styleClass) {
        return root.lookupAll("." + styleClass).stream()
                .filter(Button.class::isInstance)
                .map(n -> ((Button) n).getText())
                .toList();
    }

    @Test
    void theArticlesOwnControlsSitOnTheTabRowNotTheWindowToolbar(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            openSomething(controller, scene);
            Node root = scene.getRoot();
            List<String> onTabRow = buttonTextsIn(root, "tab-action");
            assertTrue(onTabRow.contains("Reader View"), onTabRow.toString());
            assertTrue(onTabRow.contains("Aa"), onTabRow.toString());
            assertTrue(
                    onTabRow.stream().anyMatch(t -> t.contains("☆") || t.contains("★")),
                    "the bookmark star: " + onTabRow);

            List<String> onToolbar = root.lookupAll(".tool-bar > *").stream()
                    .filter(Button.class::isInstance)
                    .map(n -> ((Button) n).getText())
                    .toList();
            assertFalse(onToolbar.contains("Reader View"), "still on the toolbar: " + onToolbar);
            return null;
        });
    }

    @Test
    void backAndForwardAreOnTheSameRow(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            openSomething(controller, scene);
            List<String> nav = buttonTextsIn(scene.getRoot(), "tab-nav");
            assertEquals(List.of("←", "→"), nav);
            return null;
        });
    }

    @Test
    void withNothingOpenTheReaderMenuOffersNothingToActOn(@TempDir Path dir) {
        // No article means no page to bookmark; an item that silently does nothing is worse than
        // no item.
        withShell(dir, (controller, scene) -> {
            assertTrue(controller.readerMenuItemsForTest().isEmpty());
            return null;
        });
    }

    @Test
    void theMenuNamesWhichOfTheTwoThingsItWillDo(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            openSomething(controller, scene);
            List<MenuItem> items = controller.readerMenuItemsForTest();
            assertFalse(items.isEmpty(), "an open article should have a menu");
            assertEquals("Bookmark This Page", items.get(0).getText());
            controller.commandsForTest().run("bookmark.toggle");
            assertEquals(
                    "Remove Bookmark",
                    controller.readerMenuItemsForTest().get(0).getText(),
                    "the label must follow the state, not assume it");
            return null;
        });
    }
}
