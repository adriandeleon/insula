package com.insula.app;

import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;
import com.insula.download.SwarmStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The swarm readout: the wording is the feature, so it is pinned. */
class SwarmTextTest {

    private static ProgressSnapshot downloading(SwarmStats swarm, long done, long rate) {
        return new ProgressSnapshot(DownloadState.DOWNLOADING, done, 1000, rate, swarm.peers(), "").withSwarm(swarm);
    }

    @Test
    void anHttpDownloadSaysNothingAboutSwarms() {
        assertEquals("", SwarmText.summary(null));
        assertEquals("", SwarmText.detail(ProgressSnapshot.of(DownloadState.DOWNLOADING, 1, 2)));
    }

    @Test
    void noPeersIsTheHeadline() {
        // The one thing a swarm readout must answer without being asked.
        assertEquals("looking for peers", SwarmText.summary(new SwarmStats(0, 0, 0, 0, 0, false)));
    }

    @Test
    void noPeersWithMirrorsIsNotAFailure() {
        // Web seeds are ordinary HTTP mirrors; a download on those alone runs at full speed.
        assertEquals("no peers · 6 mirrors", SwarmText.summary(new SwarmStats(0, 0, 0, 0, 6, false)));
        assertEquals("no peers · 1 mirror", SwarmText.summary(new SwarmStats(0, 0, 0, 0, 1, false)));
    }

    @Test
    void peersAreCountedInPlainWords() {
        assertEquals("1 peer", SwarmText.summary(new SwarmStats(1, 0, 0, 0, 0, false)));
        assertEquals("9 peers · 3 with the whole file", SwarmText.summary(new SwarmStats(9, 3, 0, 0, 0, false)));
    }

    @Test
    void theDetailExplainsWhoIsThereAndWhatWasGivenBack() {
        String text = SwarmText.detail(downloading(new SwarmStats(9, 3, 2048, 4096, 6, false), 8192, 1024));
        assertTrue(text.contains("Connected peers: 9"), text);
        assertTrue(text.contains("3 complete, 6 still downloading"), text);
        assertTrue(text.contains("HTTP mirrors in the swarm: 6"), text);
        assertTrue(text.contains("Down: "), text);
        assertTrue(text.contains("Up: "), text);
        assertTrue(text.contains("0.50×"), "the ratio is what people actually look for: " + text);
    }

    @Test
    void aSeedingArchiveDoesNotReportADownloadRate() {
        // It is not downloading; a "Down: 0 B/s" line beside it reads as a stall.
        String text = SwarmText.detail(new ProgressSnapshot(DownloadState.COMPLETED, 100, 100, 0, 2, "")
                .withSwarm(new SwarmStats(2, 1, 512, 100, 0, true)));
        assertTrue(text.startsWith("Sharing this archive with others"), text);
        assertTrue(!text.contains("Down: "), text);
        assertTrue(text.contains("Up: "), text);
    }

    @Test
    void nothingDownloadedYetMeansNoRatioRatherThanADivideByZero() {
        String text = SwarmText.detail(downloading(new SwarmStats(1, 0, 0, 0, 0, false), 0, 0));
        assertTrue(!text.contains("×"), text);
    }
}
