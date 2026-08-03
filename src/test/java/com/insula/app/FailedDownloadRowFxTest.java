package com.insula.app;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;

import com.insula.catalog.ZimEntry;
import com.insula.download.DownloadHandle;
import com.insula.download.DownloadManager;
import com.insula.download.DownloadResult;
import com.insula.download.DownloadState;
import com.insula.download.DownloadTransport;
import com.insula.download.ProgressListener;
import com.insula.download.ProgressSnapshot;
import com.insula.download.TransportSelector;
import com.insula.library.Library;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed download keeps its row, says why, and can be started again from it. */
class FailedDownloadRowFxTest {

    private static ZimEntry entry() {
        return new ZimEntry(
                "id-1",
                "MDWiki",
                "",
                "mdwiki_en_all",
                "maxi",
                List.of("eng"),
                "other",
                0,
                0,
                "https://example.invalid/mdwiki.zim.meta4",
                1024);
    }

    private static DownloadTransport failing() {
        return new DownloadTransport() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public boolean canHandle(ZimEntry e) {
                return true;
            }

            @Override
            public DownloadHandle start(ZimEntry e, Path destination, ProgressListener listener) {
                ProgressSnapshot snap =
                        new ProgressSnapshot(DownloadState.FAILED, 512, 1024, 0, 0, "the mirror went away");
                listener.onProgress(snap);
                return new DownloadHandle() {
                    @Override
                    public java.util.concurrent.CompletableFuture<DownloadResult> completion() {
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                DownloadResult.failure(destination, 512, "the mirror went away", null));
                    }

                    @Override
                    public ProgressSnapshot snapshot() {
                        return snap;
                    }

                    @Override
                    public void cancel() {}

                    @Override
                    public void pause() {}

                    @Override
                    public void resume() {}
                };
            }
        };
    }

    private static List<Button> buttonsNamed(Node root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> text.equals(b.getText()) && b.isVisible())
                .toList();
    }

    @Test
    void theRowStaysWithAReasonAndARetry(@TempDir Path dir) throws Exception {
        DownloadManager downloads = new DownloadManager(
                new TransportSelector(failing()), Library.load(dir.resolve("library.properties")), dir);
        DownloadManager.Job job = downloads.enqueue(entry());
        for (int i = 0; i < 200 && !job.snapshot().state().isTerminal(); i++) {
            Thread.sleep(20);
        }
        assertEquals(DownloadState.FAILED, job.snapshot().state());

        FxTestSupport.runOnFx(() -> {
            LibraryPane pane = new LibraryPane(
                    downloads,
                    Library.load(dir.resolve("library.properties")),
                    p -> {},
                    s -> {},
                    () -> new LibraryPane.DiskInfo(0, 0, 0, 0));
            Scene scene = new Scene(pane.node(), 900, 600);
            pane.activate();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            assertFalse(downloads.jobs().isEmpty(), "the failed job keeps its row");
            assertEquals(1, buttonsNamed(pane.node(), "Retry").size(), "a failed row offers Retry");

            // The reason is on screen, not only in the log.
            assertTrue(
                    pane.node().lookupAll(".card-sub").stream()
                            .anyMatch(n -> n instanceof javafx.scene.control.Label l
                                    && l.getText().contains("the mirror went away")),
                    "the failure reason should be readable on the row");
        });
    }

    @Test
    void aRunningDownloadOffersNoRetry(@TempDir Path dir) {
        FxTestSupport.runOnFx(() -> {
            DownloadManager downloads = new DownloadManager(
                    new TransportSelector(failing()), Library.load(dir.resolve("library.properties")), dir);
            LibraryPane pane = new LibraryPane(
                    downloads,
                    Library.load(dir.resolve("library.properties")),
                    p -> {},
                    s -> {},
                    () -> new LibraryPane.DiskInfo(0, 0, 0, 0));
            Scene scene = new Scene(pane.node(), 900, 600);
            pane.activate();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertEquals(0, buttonsNamed(pane.node(), "Retry").size());
        });
    }
}
