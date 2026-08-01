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

/**
 * Update detection end to end: catalog cache → pills on library rows → superseded-file cleanup.
 * The catalog is the seeded 7-entry fixture; nothing here touches the network.
 */
class UpdateFlowFxTest {

    private static void seedCatalog(Path configDir) throws Exception {
        Path catalogDir = configDir.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.copy(Path.of("src/test/resources/opds/groups-sample.xml"), catalogDir.resolve("entries.xml"));
        Files.writeString(
                catalogDir.resolve("catalog.properties"),
                "fetchedAt=" + System.currentTimeMillis() + "\netag=\"seeded\"\nentryCount=7\n");
    }

    /** A fake installed archive: a real file on disk plus a verified library row. */
    private static Path installArchive(Path configDir, Library library, String fileName) throws Exception {
        Path archives = configDir.resolve("archives");
        Files.createDirectories(archives);
        Path file = archives.resolve(fileName);
        Files.writeString(file, "zim-bytes-" + fileName);
        library.put(new LibraryEntry(file, fileName, Files.size(file), "", true, System.currentTimeMillis()));
        return file;
    }

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        stage.setScene(new Scene(controller.root(), 1100, 700));
        return controller;
    }

    @Test
    void newerCatalogBuildShowsAnUpdatePillOnlyForTheOutdatedFile(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        Library library = Library.load(dir.resolve("library.properties"));
        installArchive(dir, library, "wikipedia_en_all_mini_2026-01.zim"); // catalog has 2026-06
        installArchive(dir, library, "wikipedia_en_all_maxi_2026-02.zim"); // catalog's newest maxi
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.commandsForTest().run("library.open");
                controller.checkForUpdatesSyncForTest();
            });
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(
                            () -> controller.availableUpdatesForTest().size()),
                    "only the outdated mini gets an update");
            assertTrue(FxTestSupport.callOnFx(
                    () -> controller.availableUpdatesForTest().containsKey("wikipedia_en_all_mini_2026-01.zim")));
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(() -> controller.libraryPaneForTest().updatePillsForTest()),
                    "exactly one row carries a pill");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void acceptedCleanupDeletesTheSupersededFileAndClearsThePill(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        Library library = Library.load(dir.resolve("library.properties"));
        Path old = installArchive(dir, library, "wikipedia_en_all_mini_2026-01.zim");
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.supersededConfirmerForTest((names, bytes) -> true);
                controller.checkForUpdatesSyncForTest();
            });
            // Simulate the update download finishing verified.
            Path fresh = installArchive(dir, controller.libraryForTest(), "wikipedia_en_all_mini_2026-06.zim");
            FxTestSupport.runOnFx(() -> controller.afterAdmittedFile("wikipedia_en_all_mini_2026-06.zim"));

            assertFalse(Files.exists(old), "the superseded build is deleted after consent");
            assertTrue(Files.exists(fresh));
            assertTrue(
                    FxTestSupport.callOnFx(
                            () -> controller.availableUpdatesForTest().isEmpty()),
                    "with the new build installed nothing is outdated");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void declinedCleanupKeepsTheOldFile(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        Library library = Library.load(dir.resolve("library.properties"));
        Path old = installArchive(dir, library, "wikipedia_en_all_mini_2026-01.zim");
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            installArchive(dir, controller.libraryForTest(), "wikipedia_en_all_mini_2026-06.zim");
            FxTestSupport.runOnFx(() -> {
                controller.supersededConfirmerForTest((names, bytes) -> false);
                controller.afterAdmittedFile("wikipedia_en_all_mini_2026-06.zim");
            });
            assertTrue(Files.exists(old), "declining the dialog must not delete anything");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void theArchiveBeingReadIsNeverOfferedForDeletion(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        Library library = Library.load(dir.resolve("library.properties"));
        // A real, openable ZIM under an old-dated name.
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path open = archives.resolve("nons-wikibooks_2026-01.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), open);
        library.put(new LibraryEntry(open, "Wikibooks", Files.size(open), "", true, System.currentTimeMillis()));
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.openZim(open);
                controller.supersededConfirmerForTest((names, bytes) -> {
                    throw new AssertionError("must not even ask about the open archive: " + names);
                });
            });
            installArchive(dir, controller.libraryForTest(), "nons-wikibooks_2026-06.zim");
            FxTestSupport.runOnFx(() -> controller.afterAdmittedFile("nons-wikibooks_2026-06.zim"));
            assertTrue(Files.exists(open), "the file being read survives");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
