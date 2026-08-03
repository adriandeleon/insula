package com.insula.download;

import java.nio.file.Path;
import java.util.List;

import com.insula.catalog.ZimEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancelling a download must not be permanent. The manager keys jobs by entry id, and nothing
 * removed them, so the dead job was handed straight back to the next enqueue and the catalog card
 * kept rendering its final state — while the partial bytes sat on disk ready to resume.
 */
class DownloadRestartTest {

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
                "https://example.invalid/mdwiki_en_all_2025-11.zim.meta4",
                1024);
    }

    /** A transport that reports CANCELLED and never touches the network. */
    private static DownloadManager managerWith(Path dir, DownloadState reported) {
        DownloadTransport transport = new DownloadTransport() {
            @Override
            public String id() {
                return "stub";
            }

            @Override
            public boolean canHandle(ZimEntry entry) {
                return true;
            }

            @Override
            public DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener) {
                listener.onProgress(new ProgressSnapshot(reported, 512, 1024, 0, 0, ""));
                return new DownloadHandle() {
                    // A live state must not also hand back a finished future: the pipeline would
                    // run straight to a terminal state and the job under test would not be running
                    // at all. It finishes when the test cancels it, so no thread is left waiting.
                    private final java.util.concurrent.CompletableFuture<DownloadResult> done = reported.isTerminal()
                            ? java.util.concurrent.CompletableFuture.completedFuture(
                                    reported == DownloadState.CANCELLED
                                            ? DownloadResult.cancelled(destination, 512)
                                            : DownloadResult.failure(destination, 512, "no", null))
                            : new java.util.concurrent.CompletableFuture<>();

                    @Override
                    public java.util.concurrent.CompletableFuture<DownloadResult> completion() {
                        return done;
                    }

                    @Override
                    public ProgressSnapshot snapshot() {
                        return new ProgressSnapshot(reported, 512, 1024, 0, 0, "");
                    }

                    @Override
                    public void cancel() {
                        done.complete(DownloadResult.cancelled(destination, 512));
                    }

                    @Override
                    public void pause() {}

                    @Override
                    public void resume() {}
                };
            }
        };
        return new DownloadManager(
                new TransportSelector(transport),
                com.insula.library.Library.load(dir.resolve("library.properties")),
                dir);
    }

    private static void settle(DownloadManager m, ZimEntry e) throws InterruptedException {
        for (int i = 0;
                i < 200
                        && m.rawJobForTest(e) != null
                        && !m.rawJobForTest(e).snapshot().state().isTerminal();
                i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void aCancelledDownloadCanBeStartedAgain(@TempDir Path dir) throws Exception {
        DownloadManager m = managerWith(dir, DownloadState.CANCELLED);
        DownloadManager.Job first = m.enqueue(entry());
        settle(m, entry());

        assertNull(m.job(entry()), "a stopped job must not keep describing the card");
        DownloadManager.Job second = m.enqueue(entry());
        assertNotSame(first, second, "enqueue handed back the corpse instead of starting again");
        assertNotNull(second);
    }

    @Test
    void aFailedDownloadCanBeStartedAgain(@TempDir Path dir) throws Exception {
        DownloadManager m = managerWith(dir, DownloadState.FAILED);
        DownloadManager.Job first = m.enqueue(entry());
        settle(m, entry());
        assertNotSame(first, m.enqueue(entry()));
    }

    @Test
    void askingTwiceForARunningDownloadDoesNotStartASecond(@TempDir Path dir) {
        DownloadManager m = managerWith(dir, DownloadState.DOWNLOADING);
        DownloadManager.Job first = m.enqueue(entry());
        try {
            assertSame(first, m.enqueue(entry()), "one click, one download");
        } finally {
            first.cancel();
        }
    }

    @Test
    void aCancelledJobLeavesTheArrivingList(@TempDir Path dir) throws Exception {
        // A cancel is the user saying they are done; a row nothing can clear is worse than none.
        DownloadManager m = managerWith(dir, DownloadState.CANCELLED);
        m.enqueue(entry());
        settle(m, entry());
        assertTrue(m.jobs().isEmpty());
    }

    @Test
    void aFailedJobKeepsItsRowSoTheReasonStaysOnScreen(@TempDir Path dir) throws Exception {
        DownloadManager m = managerWith(dir, DownloadState.FAILED);
        m.enqueue(entry());
        settle(m, entry());
        assertEquals(1, m.jobs().size(), "a failure that vanishes takes its reason with it");
    }

    @Test
    void aFailedRowCanBeDismissed(@TempDir Path dir) throws Exception {
        DownloadManager m = managerWith(dir, DownloadState.FAILED);
        m.enqueue(entry());
        settle(m, entry());
        m.forget(entry());
        assertTrue(m.jobs().isEmpty());
        assertNull(m.rawJobForTest(entry()));
    }

    @Test
    void aRunningJobCannotBeForgotten(@TempDir Path dir) {
        // Dropping a live job would orphan a transfer with nothing left holding the handle that
        // could cancel it.
        DownloadManager m = managerWith(dir, DownloadState.DOWNLOADING);
        DownloadManager.Job job = m.enqueue(entry());
        try {
            m.forget(entry());
            assertNotNull(m.rawJobForTest(entry()));
        } finally {
            job.cancel();
        }
    }
}
