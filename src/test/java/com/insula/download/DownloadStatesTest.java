package com.insula.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which jobs are corpses and which are still worth holding on to. */
class DownloadStatesTest {

    @Test
    void aCancelledOrFailedJobIsStartedAfresh() {
        // The measured bug: these stayed in the job map forever, so enqueue returned the dead job
        // instead of starting one and the card never offered Download again.
        assertTrue(DownloadStates.restartable(DownloadState.CANCELLED));
        assertTrue(DownloadStates.restartable(DownloadState.FAILED));
    }

    @Test
    void aJobStillWorkingIsNotRestarted() {
        // Asking twice for a download already running must not start a second one.
        for (DownloadState live : new DownloadState[] {
            DownloadState.QUEUED,
            DownloadState.CONNECTING,
            DownloadState.DOWNLOADING,
            DownloadState.PAUSED,
            DownloadState.VERIFYING
        }) {
            assertFalse(DownloadStates.restartable(live), live.name());
        }
    }

    @Test
    void aJobThatProducedAFileIsTheLibrarysBusiness() {
        assertFalse(DownloadStates.restartable(DownloadState.COMPLETED));
        assertFalse(DownloadStates.restartable(DownloadState.QUARANTINED));
    }

    @Test
    void whatIsNotWorthShowingIsExactlyWhatIsRestartable() {
        for (DownloadState state : DownloadState.values()) {
            assertTrue(DownloadStates.worthShowing(state) != DownloadStates.restartable(state), state.name());
        }
    }
}
