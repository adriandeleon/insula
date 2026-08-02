package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.reader.ArticleRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two menus that fill themselves when opened — Open recent, and Recent bookmarks.
 *
 * <p>They populate in {@code setOnShowing}, which nothing in an ordinary test run ever fires, so
 * without this the whole mechanism would be untested machinery that merely compiles.
 */
class MenuDynamicsFxTest {

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        Scene scene = new Scene(controller.root(), 1000, 700);
        controller.installShortcuts(scene);
        stage.setScene(scene);
        return controller;
    }

    private static Menu submenu(ReaderController controller, String menuTitle, String submenuTitle) {
        return FxTestSupport.callOnFx(() -> controller.menuBarForTest().getMenus().stream()
                .filter(m -> menuTitle.equals(m.getText()))
                .flatMap(m -> m.getItems().stream())
                .filter(i -> i instanceof Menu && submenuTitle.equals(i.getText()))
                .map(Menu.class::cast)
                .findFirst()
                .orElse(null));
    }

    /** Opens the submenu the way the mouse would, and reads back what it filled itself with. */
    private static List<String> openAndRead(Menu menu) {
        return FxTestSupport.callOnFx(() -> {
            menu.getOnShowing().handle(new Event(Menu.ON_SHOWING));
            return menu.getItems().stream().map(MenuItem::getText).toList();
        });
    }

    private static boolean allDisabled(Menu menu) {
        return FxTestSupport.callOnFx(() -> menu.getItems().stream().allMatch(MenuItem::isDisable));
    }

    @Test
    void openRecentListsArchivesAsTheyAreOpened(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            Menu recent = submenu(controller, "File", "Open recent");
            assertNotNull(recent, "the File menu carries an Open recent submenu");

            // An empty submenu would expand into nothing, which reads as broken rather than as
            // "you have none".
            assertEquals(List.of("Nothing opened yet"), openAndRead(recent));
            assertTrue(allDisabled(recent), "the placeholder is not clickable");

            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            assertTrue(
                    openAndRead(recent).stream().anyMatch(t -> t.endsWith("wikibooks.zim")),
                    "the archive just opened is the first thing offered next time");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void anArchiveThatIsGoneIsNotOffered(@TempDir Path dir) throws Exception {
        // A recent entry that can only ever report failure is worse than a shorter list.
        Path zim = dir.resolve("doomed.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            Menu recent = submenu(controller, "File", "Open recent");
            assertTrue(openAndRead(recent).stream().anyMatch(t -> t.endsWith("doomed.zim")));

            FxTestSupport.runOnFx(controller::closeArchiveForTest);
            Files.delete(zim);
            assertEquals(List.of("Nothing opened yet"), openAndRead(recent));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void recentBookmarksAppearUnderTheBookmarksMenu(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("a.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            Menu marks = submenu(controller, "Bookmarks", "Recent bookmarks");
            assertNotNull(marks);
            assertEquals(List.of("No bookmarks yet"), openAndRead(marks));

            FxTestSupport.runOnFx(() ->
                    controller.bookmarksForTest().addFirst(new ArticleRef(zim, "Main_Page", "Main Page", "Wikibooks")));
            assertTrue(
                    openAndRead(marks).stream().anyMatch(t -> t.startsWith("Main Page")),
                    "the menu reads the store when it opens, so no refresh call is needed");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
