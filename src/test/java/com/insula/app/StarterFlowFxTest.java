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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void homeOnFirstRunShowsTheTaglineAndTheSameStarters(@TempDir Path dir) throws Exception {
        // The kit's first run is the same frame with starters where the history would be, so Home
        // and the Library must offer identical picks rather than each resolving its own.
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("home.open");
                controller.checkForUpdatesSyncForTest();
            });
            assertTrue(FxTestSupport.callOnFx(() -> controller.homePaneForTest().showsTaglineForTest()));
            assertEquals(
                    2,
                    (int) FxTestSupport.callOnFx(
                            () -> controller.homePaneForTest().starterRowsForTest()),
                    "Home offers the same resolvable picks as the Library");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void aStockedButUnreadDeviceIsToldToSearchRatherThanToDownload(@TempDir Path dir) throws Exception {
        // Nothing read yet is not the same as nothing installed; offering downloads to someone who
        // already has a library is nagging.
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
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("home.open");
                controller.checkForUpdatesSyncForTest();
            });
            assertFalse(
                    FxTestSupport.callOnFx(() -> controller.homePaneForTest().showsTaglineForTest()),
                    "the first-run pitch belongs to an empty device only");
            assertEquals(0, (int)
                    FxTestSupport.callOnFx(() -> controller.homePaneForTest().starterRowsForTest()));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void firstRunOffersBothWaysOut(@TempDir Path dir) throws Exception {
        // Someone handed a .zim on a USB stick has nothing to download; without the second link
        // an empty library reads as a dead end for them.
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("home.open");
                controller.checkForUpdatesSyncForTest();
            });
            assertEquals(
                    java.util.List.of("Catalog", "open a .zim file"),
                    FxTestSupport.callOnFx(() -> controller.homePaneForTest().firstRunLinksForTest()));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
