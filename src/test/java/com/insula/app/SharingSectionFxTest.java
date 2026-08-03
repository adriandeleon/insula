package com.insula.app;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.download.TorrentTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sharing section: where it sits, and whether a shared archive can be seen and stopped.
 *
 * <p>Seeding used to be entirely invisible — a libtorrent session left running with no row, no
 * stop, and nothing to say it was there.
 */
class SharingSectionFxTest {

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
    void sharingSitsAboveTheShelfRatherThanBelowIt(@TempDir Path dir) {
        // At the foot of the Library it is below however many archives you own, which is exactly
        // where something you may be doing without realising must not be.
        withShell(dir, (controller, settings) -> {
            LibraryPane pane = controller.libraryPaneForTest();
            assertTrue(
                    pane.sharingIndexForTest() < pane.shelfIndexForTest(),
                    "sharing at " + pane.sharingIndexForTest() + ", shelf at " + pane.shelfIndexForTest());
            assertEquals(1, pane.sharingIndexForTest(), "directly under the disk gauge");
            return null;
        });
    }

    @Test
    void aSharedArchiveGetsARowThatCanStopIt(@TempDir Path dir) {
        AtomicBoolean stopped = new AtomicBoolean();
        withShell(dir, (controller, settings) -> {
            LibraryPane pane = controller.libraryPaneForTest();
            assertEquals(0, pane.seedRowsForTest(), "nothing shared, nothing shown");

            TorrentTransport.Seed seed =
                    new TorrentTransport.Seed("wikipedia_en_all.zim", null, null, () -> stopped.set(true));
            pane.setSeedsSupplier(() -> stopped.get() ? List.of() : List.of(seed));
            pane.activate();
            assertEquals(1, pane.seedRowsForTest(), "a shared archive is visible");

            pane.stopFirstSeedForTest();
            assertTrue(stopped.get(), "and stoppable");
            assertEquals(0, pane.seedRowsForTest(), "and the row goes with it");
            return null;
        });
    }

    @Test
    void aSeedWhoseHandleIsGoneReadsAsZeroRatherThanThrowing(@TempDir Path dir) {
        // The handle is native and can vanish under us; a Library that throws while painting is
        // worse than one reporting an idle share.
        TorrentTransport.Seed seed = new TorrentTransport.Seed("gone.zim", null, null, () -> {});
        assertEquals(0, seed.stats().peers());
        assertTrue(seed.stats().seeding());
    }
}
