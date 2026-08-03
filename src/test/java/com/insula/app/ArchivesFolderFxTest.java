package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.library.LibraryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archives folder as a setting: resolution, persistence, and where downloads then land. */
class ArchivesFolderFxTest {

    private static <T> T withShell(Path dir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(controller.root(), 900, 600));
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void unsetMeansBesideTheRestOfTheConfig(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertEquals(dir.resolve("archives"), controller.archivesDir());
            return null;
        });
    }

    @Test
    void aChosenFolderIsUsedAndRemembered(@TempDir Path dir) throws Exception {
        Path elsewhere = Files.createDirectories(dir.resolve("big/archives"));
        withShell(dir, (controller, settings) -> {
            settings.setArchivesFolder(elsewhere);
            settings.save();
            return null;
        });

        Settings reloaded = Settings.load(dir.resolve("settings.properties"));
        assertEquals(elsewhere, reloaded.getArchivesFolder(dir.resolve("archives")));
        withShell(dir, (controller, settings) -> {
            assertEquals(elsewhere, controller.archivesDir(), "and the next launch downloads there");
            assertEquals(elsewhere, controller.downloadsForTest().downloadDir());
            return null;
        });
    }

    @Test
    void clearingItFallsBackToTheDefaultRatherThanAnEmptyPath(@TempDir Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        settings.setArchivesFolder(dir.resolve("x"));
        settings.setArchivesFolder(null);
        assertEquals(dir.resolve("archives"), settings.getArchivesFolder(dir.resolve("archives")));
    }

    @Test
    void movingRelocatesTheFileAndTheIndexEntryTogether(@TempDir Path dir) throws Exception {
        // The property that matters: after any outcome every entry points at a file that exists.
        Path from = Files.createDirectories(dir.resolve("archives"));
        Path to = Files.createDirectories(dir.resolve("moved"));
        Path zim = from.resolve("a.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);

        com.insula.library.Library library = com.insula.library.Library.load(dir.resolve("library.properties"));
        library.put(new LibraryEntry(zim, "A", Files.size(zim), "", true, 0));
        library.save();

        var plan = com.insula.library.ArchiveMove.plan(library.entries(), from, to, Files::exists);
        assertEquals(1, plan.steps().size());
        var step = plan.steps().getFirst();
        Files.move(step.from(), step.to());
        library.relocate(step.from(), step.entry().withFile(step.to()));
        library.save();

        com.insula.library.Library reloaded = com.insula.library.Library.load(dir.resolve("library.properties"));
        Path recorded = reloaded.entries().getFirst().file();
        assertEquals(to.resolve("a.zim"), recorded);
        assertTrue(Files.isRegularFile(recorded), "the index points at a file that is really there");
    }
}
