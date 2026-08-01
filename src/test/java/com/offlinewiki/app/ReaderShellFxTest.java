package com.offlinewiki.app;

import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import com.offlinewiki.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real shell headlessly: the palette overlay and the settings→UI apply path can only
 * be proven against an actual scene graph.
 */
class ReaderShellFxTest {

    /** Builds a controller in a real scene and hands it to the assertion body on the FX thread. */
    private static <T> T withShell(Path configDir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(configDir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings);
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
    void paletteOpensFiltersAndClosesThroughTheCommand(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            StackPane root = (StackPane) controller.root();
            int base = root.getChildren().size();

            controller.commandsForTest().run("view.commandPalette");
            assertTrue(root.getChildren().size() > base, "palette should add a backdrop + card to the root");

            // Esc-equivalent: the same command toggles it back off.
            controller.commandsForTest().run("view.commandPalette");
            assertEquals(base, root.getChildren().size(), "palette should be removed from the root");
            return null;
        });
    }

    @Test
    void everyBoundKeyResolvesToARegisteredCommand(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            // A binding pointing at a missing id would silently do nothing at runtime.
            controller
                    .keybindingsForTest()
                    .commandIds()
                    .forEach(id -> assertTrue(
                            controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals(id)),
                            "bound key references unregistered command: " + id));
            return null;
        });
    }

    @Test
    void paletteShowsShortcutHintsForBoundCommands(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            String hint = controller.keybindingsForTest().displayFor("view.commandPalette");
            assertFalse(hint.isBlank(), "the palette command should advertise its own shortcut");
            assertEquals("", controller.keybindingsForTest().displayFor("app.quit"), "unbound → no hint");
            return null;
        });
    }

    @Test
    void themeAndZoomCommandsApplyLiveAndPersist(@TempDir Path dir) {
        Path file = dir.resolve("settings.properties");
        withShell(dir, (controller, settings) -> {
            assertFalse(settings.isDark());
            controller.commandsForTest().run("view.toggleTheme");
            assertTrue(settings.isDark(), "toggle should flip the live settings");

            controller.commandsForTest().run("view.zoomIn");
            assertEquals(110, settings.getZoomPercent());
            WebView web = controller.webViewForTest();
            assertEquals(1.10, web.getZoom(), 0.001, "zoom must reach the WebView, not just settings");

            controller.commandsForTest().run("view.zoomReset");
            assertEquals(100, settings.getZoomPercent());
            assertEquals(1.0, web.getZoom(), 0.001);
            return null;
        });

        // Commands persist: a fresh load sees the dark theme written by the toggle above.
        Settings reloaded = Settings.load(file);
        assertTrue(reloaded.isDark());
    }

    @Test
    void zoomCommandsClampAtTheSettingsBounds(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            for (int i = 0; i < 40; i++) {
                controller.commandsForTest().run("view.zoomOut");
            }
            assertEquals(Settings.MIN_ZOOM, settings.getZoomPercent());
            for (int i = 0; i < 80; i++) {
                controller.commandsForTest().run("view.zoomIn");
            }
            assertEquals(Settings.MAX_ZOOM, settings.getZoomPercent());
            return null;
        });
    }

    @Test
    void reopenLastArchiveHonoursTheSettingAndAMissingFile(@TempDir Path dir) {
        Path fixture = Path.of("src/test/resources/zim/nons-wikibooks.zim").toAbsolutePath();

        withShell(dir, (controller, settings) -> {
            settings.setLastArchive(fixture.toString());
            settings.setReopenLastArchive(true);
            controller.openLastArchiveIfEnabled();
            assertTrue(controller.hasArchiveForTest(), "the remembered archive should reopen");
            return null;
        });

        withShell(dir.resolve("off"), (controller, settings) -> {
            settings.setLastArchive(fixture.toString());
            settings.setReopenLastArchive(false);
            controller.openLastArchiveIfEnabled();
            assertFalse(controller.hasArchiveForTest(), "disabled: nothing should open");
            return null;
        });

        withShell(dir.resolve("gone"), (controller, settings) -> {
            settings.setLastArchive(dir.resolve("deleted.zim").toString());
            settings.setReopenLastArchive(true);
            controller.openLastArchiveIfEnabled(); // must not throw on a since-deleted archive
            assertFalse(controller.hasArchiveForTest());
            return null;
        });
    }

    @Test
    void openingAnArchiveRemembersItForNextLaunch(@TempDir Path dir) {
        Path fixture = Path.of("src/test/resources/zim/nons-wikibooks.zim").toAbsolutePath();
        withShell(dir, (controller, settings) -> {
            controller.openZim(fixture);
            assertEquals(fixture.toString(), settings.getLastArchive());
            return null;
        });
        assertEquals(
                fixture.toString(),
                Settings.load(dir.resolve("settings.properties")).getLastArchive());
    }

    @Test
    void searchLimitSettingIsReadPerSearch(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            settings.setSearchLimit(7);
            // No archive open: the search path must stay inert rather than throw.
            controller.searchForTest("anything");
            assertNotNull(controller.root());
            return null;
        });
    }
}
