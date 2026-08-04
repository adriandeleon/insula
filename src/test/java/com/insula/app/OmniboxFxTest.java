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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The omnibox searches, which is what it has always said it does. */
class OmniboxFxTest {

    private static <T> T withShell(Path dir, BiFunction<ReaderController, Scene, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1200, 800);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, scene);
            } finally {
                controller.dispose();
            }
        });
    }

    /** Asked of the controller rather than looked up by style class: a CSS lookup needs a laid-out
     *  scene, and this test is about behaviour rather than about layout. */
    private static TextField omnibox(ReaderController controller) {
        return controller.omniFieldForTest();
    }

    @Test
    void itAcceptsTyping(@TempDir Path dir) {
        // It used to be uneditable and open the command palette on click, while its own
        // placeholder read "Search this archive - 318,179 articles".
        withShell(dir, (controller, scene) -> {
            TextField omni = omnibox(controller);
            assertTrue(omni.isEditable(), "a search box you cannot type in is not a search box");
            return null;
        });
    }

    @Test
    void typingInItSearches(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            omnibox(controller).setText("charles");
            assertEquals("charles", controller.searchFieldTextForTest(), "one query, two ways in");
            return null;
        });
    }

    @Test
    void theSidebarFieldDrivesItBack(@TempDir Path dir) {
        // Both show the same query, so neither can quietly disagree with the results on screen.
        withShell(dir, (controller, scene) -> {
            controller.setSearchFieldTextForTest("piano");
            assertEquals("piano", omnibox(controller).getText());
            return null;
        });
    }

    @Test
    void searchingRevealsSomewhereToPutTheResults(@TempDir Path dir) {
        withShell(dir, (controller, scene) -> {
            controller.settingsForTest().setSidebarVisible(false);
            omnibox(controller).setText("anything");
            assertTrue(controller.settingsForTest().isSidebarVisible(), "results need to be visible to be results");
            return null;
        });
    }

    @Test
    void thePlaceholderDescribesWhatItActuallySearches(@TempDir Path dir) {
        // On the Catalog surface it used to promise to search the catalog, which has its own box.
        withShell(dir, (controller, scene) -> {
            controller.commandsForTest().run("catalog.open");
            String prompt = controller.omniPlaceholderForTest();
            assertTrue(prompt.toLowerCase().contains("everything you have") || prompt.equals("Search"), prompt);
            return null;
        });
    }
}
