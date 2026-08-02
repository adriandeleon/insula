package com.insula.app;

import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state vocabulary: one pill means one thing, wherever it appears. */
class PillsTest {

    private static ProgressSnapshot at(DownloadState state, long done, long total) {
        return new ProgressSnapshot(state, done, total, 0, 0, "");
    }

    @Test
    void verifyingIsItsOwnVisibleStateNotAShadeOfDownloading() {
        // The kit is explicit: integrity is the promise, so it never hides inside a progress bar.
        var verifying = Pills.forDownload(at(DownloadState.VERIFYING, 71, 100));
        assertTrue(verifying.getText().contains("Verifying"), verifying.getText());
        assertTrue(verifying.getText().contains("SHA-256"), "it names what is being checked");
        assertTrue(verifying.getStyleClass().contains("pill-amber"));

        var downloading = Pills.forDownload(at(DownloadState.DOWNLOADING, 42, 100));
        assertTrue(downloading.getStyleClass().contains("pill-accent"), "different state, different tone");
    }

    @Test
    void repairAdvertisesTheCostOfTheFixNotTheSizeOfTheLoss() {
        // A quarantined 2.1 GB archive whose repair is 12 MB must say 12 MB, or the user
        // reasonably concludes the download was wasted.
        var pill = Pills.repair("12 MB");
        assertEquals("Repair · 12 MB", pill.getText());
        assertTrue(pill.getStyleClass().contains("pill-coral"));
    }

    @Test
    void anUpdateNamesBothTheBuildAndThePrice() {
        // Accepting an update is a bandwidth decision, so both facts are on the pill.
        assertEquals(
                "Update · 2026-08 · 110 GB", Pills.update("2026-08", "110 GB").getText());
    }

    @Test
    void everyDownloadStateHasAPill() {
        for (DownloadState state : DownloadState.values()) {
            var pill = Pills.forDownload(at(state, 1, 2));
            assertTrue(pill.getText() != null && !pill.getText().isBlank(), state + " had no words");
            assertTrue(pill.getStyleClass().stream().anyMatch(c -> c.startsWith("pill-")), state + " had no tone");
        }
    }

    @Test
    void transportPillsReadNaturallyWithAndWithoutSources() {
        assertEquals("HTTP · 6 mirrors", Pills.transport("HTTP", 6, "mirrors").getText());
        assertEquals(
                "BitTorrent · 4 peers",
                Pills.transport("BitTorrent", 4, "peers").getText());
        assertEquals("HTTP", Pills.transport("HTTP", 0, "mirrors").getText(), "no count, no noise");
    }
}
