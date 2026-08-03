package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The app shell: menubar, the four surfaces, and the design kit's key map. */
class ShellFxTest {

    private static <T> T withShell(Path dir, java.util.function.Function<ReaderController, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1200, 800);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void everyMenuItemNamesARealCommand(@TempDir Path dir) {
        // The menubar is built from command ids so it cannot drift from the palette. A menu item
        // whose command was renamed would otherwise just vanish silently.
        withShell(dir, controller -> {
            assertEquals(
                    List.of(),
                    Menus.missingCommands(controller.commandsForTest()),
                    "menu items pointing at commands that no longer exist");
            return null;
        });
    }

    @Test
    void theMenubarOnScreenActuallyHasItems(@TempDir Path dir) {
        // Asserting against a menubar the test builds itself proves only that Menus.build works
        // given a populated registry — it cannot see the assembly, where the bar is built before
        // the commands are registered and every menu comes out empty.
        withShell(dir, controller -> {
            var bar = controller.menuBarForTest();
            assertEquals(6, bar.getMenus().size());
            for (var menu : bar.getMenus()) {
                // Separators are added unconditionally, so "not empty" is not the test — a menu
                // that lost every command still renders as a stack of bare dividers.
                long real = menu.getItems().stream()
                        .filter(i -> !(i instanceof javafx.scene.control.SeparatorMenuItem))
                        .count();
                assertTrue(real > 0, "the " + menu.getText() + " menu has no commands on screen");
            }
            return null;
        });
    }

    @Test
    void theMenubarCarriesTheKitsSixMenus(@TempDir Path dir) {
        withShell(dir, controller -> {
            var bar = Menus.build(controller.commandsForTest(), controller.keybindingsForTest());
            assertEquals(
                    List.of("File", "View", "Bookmarks", "Library", "Catalog", "Help"),
                    bar.getMenus().stream()
                            .map(javafx.scene.control.Menu::getText)
                            .toList());
            // Accelerators come from the same Keybindings the scene installs, so a menu can never
            // advertise a chord that does nothing.
            var view = bar.getMenus().get(1);
            var home = view.getItems().stream()
                    .filter(i -> "Show Home".equals(i.getText()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(controller.keybindingsForTest().combinationFor("home.open"), home.getAccelerator());
            return null;
        });
    }

    @Test
    void theKitsKeyMapIsWhatIsBound(@TempDir Path dir) {
        withShell(dir, controller -> {
            var keys = controller.keybindingsForTest();
            assertEquals("Ctrl+1", keys.displayFor("home.open"));
            assertEquals("Ctrl+2", keys.displayFor("library.open"));
            assertEquals("Ctrl+3", keys.displayFor("catalog.open"));
            assertEquals("Ctrl+B", keys.displayFor("bookmark.show"), "B opens the bookmarks panel");
            assertEquals("Ctrl+H", keys.displayFor("history.show"));
            assertEquals("Ctrl+F", keys.displayFor("search.focus"), "F searches within the archive");
            assertEquals("Ctrl+R", keys.displayFor("catalog.refresh"));
            assertEquals("Ctrl+L", keys.displayFor("lan.share"), "L shares on the network, not search");
            assertEquals("Ctrl+Shift+T", keys.displayFor("tab.reopen"));
            return null;
        });
    }

    @Test
    void everyBoundChordPointsAtACommandThatExists(@TempDir Path dir) {
        withShell(dir, controller -> {
            var registry = controller.commandsForTest();
            List<String> dangling = controller.keybindingsForTest().commandIds().stream()
                    .filter(id -> registry.all().stream().noneMatch(c -> c.id().equals(id)))
                    .toList();
            assertEquals(List.of(), dangling, "chords bound to commands that do not exist");
            return null;
        });
    }

    @Test
    void noTwoCommandsShareAChord(@TempDir Path dir) {
        // A collision is silent: install() maps chord → action, so the last binding wins and the
        // other command becomes unreachable with no error at all. Ctrl+R really was bound to both
        // the catalog refresh and the reader-mode cycle.
        withShell(dir, controller -> {
            assertEquals(
                    java.util.Map.of(),
                    controller.keybindingsForTest().conflicts(),
                    "two commands cannot share one chord — one of them would be unreachable");
            return null;
        });
    }

    @Test
    void theReaderSurfaceAppearsOnlyOnceSomethingIsOpen(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);

        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1200, 800));
            return c;
        });
        try {
            assertFalse(
                    FxTestSupport.callOnFx(controller::readerSurfaceVisibleForTest),
                    "with nothing open, Reader is not a place you can go");
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            assertTrue(
                    FxTestSupport.callOnFx(controller::readerSurfaceVisibleForTest),
                    "opening an archive reveals the Reader surface");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void surfacesSwitchAndHomeIsReachable(@TempDir Path dir) {
        withShell(dir, controller -> {
            controller.commandsForTest().run("home.open");
            assertEquals(SurfaceCoordinator.Surface.HOME, controller.surfaceForTest());
            controller.commandsForTest().run("catalog.open");
            assertEquals(SurfaceCoordinator.Surface.CATALOG, controller.surfaceForTest());
            controller.commandsForTest().run("library.open");
            assertEquals(SurfaceCoordinator.Surface.LIBRARY, controller.surfaceForTest());
            return null;
        });
    }

    @Test
    void theOmniboxStatesItsScope(@TempDir Path dir) {
        withShell(dir, controller -> {
            controller.commandsForTest().run("home.open");
            String placeholder = controller.omniPlaceholderForTest();
            assertTrue(placeholder.startsWith("Search everything you have"), placeholder);
            controller.commandsForTest().run("catalog.open");
            assertTrue(controller.omniPlaceholderForTest().contains("catalog"), "scope follows the surface");
            return null;
        });
    }

    @Test
    void reopeningAClosedTabIsSafeWithNothingClosed(@TempDir Path dir) {
        withShell(dir, controller -> {
            assertEquals(0, controller.closedTabCountForTest());
            assertTrue(controller.commandsForTest().run("tab.reopen"), "it reports rather than throwing");
            return null;
        });
    }
}
