package com.insula.app;

import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.library.UpdateReplacement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grouped Settings sidebar, and the two new preferences reaching the things they govern. */
class SettingsGroupsFxTest {

    private static <T> T withDialog(Path dir, java.util.function.BiFunction<SettingsDialog, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            SettingsDialog dialog = new SettingsDialog(new Stage(), settings, () -> {});
            dialog.setArchivesFolder(dir.resolve("archives"), () -> {});
            dialog.sync();
            return body.apply(dialog, settings);
        });
    }

    @Test
    void aGroupHeadingIsStructureRatherThanADestination(@TempDir Path dir) {
        // Arrowing onto "READING" and getting a blank page would be a dead end in a list whose
        // whole job is navigation.
        withDialog(dir, (dialog, settings) -> {
            ListView<Object> sidebar = dialog.sidebarForTest();
            assertTrue(sidebar.getItems().size() > 6, "groups plus categories");
            assertFalse(
                    sidebar.getSelectionModel().getSelectedItem() instanceof Record,
                    "the initial selection is a real category, not a heading");
            assertInstanceOf(String.class, sidebar.getSelectionModel().getSelectedItem());
            return null;
        });
    }

    @Test
    void everyCategoryInTheSidebarHasAPage(@TempDir Path dir) {
        // A row that selects nothing is invisible in review and obvious in use.
        withDialog(dir, (dialog, settings) -> {
            ListView<Object> sidebar = dialog.sidebarForTest();
            for (Object row : sidebar.getItems()) {
                if (row instanceof String name) {
                    sidebar.getSelectionModel().select(row);
                    assertFalse(dialog.pageEmptyForTest(), "no page behind " + name);
                }
            }
            return null;
        });
    }

    @Test
    void theUpdatePolicyAndDownloadLimitPersist(@TempDir Path dir) {
        withDialog(dir, (dialog, settings) -> {
            assertEquals(UpdateReplacement.Policy.ASK, settings.getUpdatePolicy(), "asking is the safe default");
            settings.setUpdatePolicy(UpdateReplacement.Policy.KEEP);
            settings.setMaxConcurrentDownloads(5);
            settings.save();
            return null;
        });
        Settings reloaded = Settings.load(dir.resolve("settings.properties"));
        assertEquals(UpdateReplacement.Policy.KEEP, reloaded.getUpdatePolicy());
        assertEquals(5, reloaded.getMaxConcurrentDownloads());
    }

    @Test
    void theDownloadLimitIsClampedRatherThanTakenLiterally(@TempDir Path dir) {
        // Typed into an editable spinner, or written by hand into the properties file.
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        settings.setMaxConcurrentDownloads(9999);
        assertEquals(Settings.MAX_CONCURRENT_DOWNLOADS, settings.getMaxConcurrentDownloads());
        settings.setMaxConcurrentDownloads(0);
        assertEquals(Settings.MIN_CONCURRENT_DOWNLOADS, settings.getMaxConcurrentDownloads());
    }

    @Test
    void theLimitReachesTheDownloadPool(@TempDir Path dir) {
        // A concurrency setting that only takes effect after a restart is not worth having.
        FxTestSupport.runOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            settings.setMaxConcurrentDownloads(4);
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(controller.root(), 900, 600));
            try {
                controller.applySettings();
                assertEquals(4, controller.downloadsForTest().concurrencyForTest());
                settings.setMaxConcurrentDownloads(1);
                controller.applySettings();
                assertEquals(1, controller.downloadsForTest().concurrencyForTest(), "and shrinks again");
            } finally {
                controller.dispose();
            }
        });
    }
}
