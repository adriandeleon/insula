package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** First-run starters: shown only while the library is empty, resolved from the cached catalog. */
class StarterFlowFxTest {

    private static void seedCatalog(Path configDir, String fixture, int count) throws Exception {
        Path catalogDir = configDir.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.copy(Path.of("src/test/resources/opds/" + fixture), catalogDir.resolve("entries.xml"));
        Files.writeString(
                catalogDir.resolve("catalog.properties"),
                "fetchedAt=" + System.currentTimeMillis() + "\netag=\"seeded\"\nentryCount=" + count + "\n");
    }

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        stage.setScene(new Scene(controller.root(), 1100, 700));
        return controller;
    }

    @Test
    void emptyLibraryShowsTheResolvableStarters(@TempDir Path dir) throws Exception {
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            int rows = FxTestSupport.callOnFx(() -> {
                controller.commandsForTest().run("library.open");
                controller.checkForUpdatesSyncForTest();
                return controller.libraryPaneForTest().starterRowsForTest();
            });
            // The fixture carries ray-charles + wikivoyage; the other two picks are absent.
            assertEquals(2, rows, "only names the catalog resolves become starter rows");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void nonEmptyLibraryShowsNoStarters(@TempDir Path dir) throws Exception {
        seedCatalog(dir, "starters-sample.xml", 3);
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        Library library = Library.load(dir.resolve("library.properties"));
        library.put(new LibraryEntry(zim, "Wikibooks", Files.size(zim), "", true, System.currentTimeMillis()));
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            int rows = FxTestSupport.callOnFx(() -> {
                controller.commandsForTest().run("library.open");
                controller.checkForUpdatesSyncForTest();
                return controller.libraryPaneForTest().starterRowsForTest();
            });
            assertEquals(0, rows, "a stocked library never nags with starters");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void noCatalogCacheDegradesToTheStoreCallToAction(@TempDir Path dir) {
        // No seeded catalog at all: no network is touched, no starters appear.
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            int rows = FxTestSupport.callOnFx(() -> {
                controller.commandsForTest().run("library.open");
                controller.checkForUpdatesSyncForTest();
                return controller.libraryPaneForTest().starterRowsForTest();
            });
            assertEquals(0, rows);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
