package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.catalog.ZimEntry;
import com.insula.config.Settings;
import com.insula.library.LibraryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The state function a Catalog card is painted from, against the real DownloadManager and Library.
 *
 * <p>The card lifecycle test drives the pane with a fake supplier, which proves the pane repaints
 * but says nothing about whether the app reports the right thing. These are the two assumptions
 * the fix rests on: that a job is visible the instant it is enqueued, and that an admitted archive
 * reads as installed.
 */
class CatalogCardStateFxTest {

    private static ZimEntry entry(String fileName) {
        return new ZimEntry(
                "urn:uuid:card-state",
                "Test Archive",
                "",
                fileName.replace(".zim", ""),
                "",
                List.of("eng"),
                "other",
                1,
                0,
                // The file name is derived from this URL, not from the name field. Port 1 is
                // deliberately unreachable: this test is about bookkeeping, not transfer.
                "http://127.0.0.1:1/" + fileName + ".meta4",
                1024);
    }

    private static <T> T withShell(Path dir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(controller.root(), 1000, 700));
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void anEnqueuedDownloadIsVisibleToTheCardImmediately(@TempDir Path dir) {
        // The card repaints the moment the button is pressed, so a job registered a tick later
        // would leave the first frame saying "Download" over a transfer already running.
        withShell(dir, (controller, settings) -> {
            ZimEntry entry = entry("pending.zim");
            assertEquals(
                    CatalogPane.Installed.NO,
                    controller.catalogCardStateForTest(entry).installed());
            assertEquals(
                    null, controller.catalogCardStateForTest(entry).snapshot(), "nothing in flight before the click");

            controller.downloadsForTest().enqueue(entry);
            assertNotNull(
                    controller.catalogCardStateForTest(entry).snapshot(),
                    "the job is in the map before enqueue returns");
            return null;
        });
    }

    @Test
    void anArchiveInTheLibraryReadsAsInstalled(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("installed.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);

        com.insula.library.Library library = com.insula.library.Library.load(dir.resolve("library.properties"));
        library.put(new LibraryEntry(zim, "Installed", Files.size(zim), "", true, System.currentTimeMillis()));
        library.save();

        withShell(dir, (controller, settings) -> {
            assertEquals(
                    CatalogPane.Installed.YES,
                    controller.catalogCardStateForTest(entry("installed.zim")).installed(),
                    "which is what turns the button into Open and the pill into In library");
            return null;
        });
    }
}
