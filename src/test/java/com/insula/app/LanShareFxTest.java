package com.insula.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LAN sharing through the shell: verified archives get served, stop tears everything down. */
class LanShareFxTest {

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        settings.setLanPort(0); // ephemeral: tests must not fight over a fixed port
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        stage.setScene(new Scene(controller.root(), 1100, 700));
        return controller;
    }

    @Test
    void sharingServesVerifiedArchivesAndStopCleansUp(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        Library library = Library.load(dir.resolve("library.properties"));
        library.put(new LibraryEntry(zim, "Wikibooks", Files.size(zim), "", true, System.currentTimeMillis()));
        library.save();

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            String url = FxTestSupport.callOnFx(controller::startLanSharing);
            assertNotNull(url, "sharing must start with one verified archive");
            int port =
                    FxTestSupport.callOnFx(() -> controller.lanServerForTest().port());

            HttpResponse<String> index = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("Wikibooks"));

            FxTestSupport.runOnFx(controller::stopLanSharing);
            assertNull(FxTestSupport.callOnFx(controller::lanServerForTest));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void sharingRefusesAnEmptyLibrary(@TempDir Path dir) {
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            assertNull(FxTestSupport.callOnFx(controller::startLanSharing));
            assertNull(FxTestSupport.callOnFx(controller::lanServerForTest));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void shareCommandIsRegistered(@TempDir Path dir) {
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            assertTrue(FxTestSupport.callOnFx(
                    () -> controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals("lan.share"))));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void qrRendersScannableModulesWithAQuietZone() {
        javafx.scene.image.WritableImage image =
                FxTestSupport.callOnFx(() -> QrImage.render("http://192.168.1.20:8181/", 4, 2));
        // The border is light...
        assertEquals(javafx.scene.paint.Color.WHITE, image.getPixelReader().getColor(0, 0));
        // ...and the top-left finder pattern's corner module is dark, just inside the border.
        int inset = 2 * 4; // border modules × scale
        assertEquals(javafx.scene.paint.Color.BLACK, image.getPixelReader().getColor(inset, inset));
        assertTrue(image.getWidth() > 21 * 4, "at least version-1 QR plus borders");
    }
}
