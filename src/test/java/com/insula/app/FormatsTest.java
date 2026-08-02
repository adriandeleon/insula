package com.insula.app;

import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {

    @Test
    void scalesByteSizes() {
        assertEquals("512 B", Formats.bytes(512));
        assertEquals("1.0 KB", Formats.bytes(1024));
        assertEquals("1.0 MB", Formats.bytes(1024 * 1024));
        assertEquals("110 GB", Formats.bytes(110L * 1024 * 1024 * 1024));
        assertEquals("—", Formats.bytes(-1));
    }

    @Test
    void formatsRatesAndDurations() {
        assertEquals("1.0 MB/s", Formats.rate(1024 * 1024));
        assertEquals("", Formats.rate(0));
        assertEquals("45s", Formats.duration(45));
        assertEquals("2m 5s", Formats.duration(125));
        assertEquals("1h 1m", Formats.duration(3660));
        assertEquals("", Formats.duration(-1));
    }

    @Test
    void everyStateRendersSomething() {
        // A state with no label would show a blank row, which reads as a hung download.
        for (DownloadState state : DownloadState.values()) {
            ProgressSnapshot snapshot = new ProgressSnapshot(state, 10, 100, 0, 0, "");
            assertTrue(!Formats.progressLine(snapshot).isBlank(), "no text for " + state);
        }
    }

    @Test
    void downloadingLineCarriesSizeRateAndEta() {
        ProgressSnapshot s = new ProgressSnapshot(
                DownloadState.DOWNLOADING, 50L * 1024 * 1024, 100L * 1024 * 1024, 1024 * 1024, 3, "");
        String line = Formats.progressLine(s);
        assertTrue(line.contains("50.0 MB of 100 MB"), line);
        assertTrue(line.contains("1.0 MB/s"), line);
        assertTrue(line.contains("left"), line);
        assertTrue(line.contains("3 sources"), line);
    }

    @Test
    void verifyingIsVisiblyDistinctFromDownloading() {
        // On a large archive this phase runs for minutes; if it looked idle the app seems hung.
        String line = Formats.progressLine(new ProgressSnapshot(DownloadState.VERIFYING, 5, 100, 0, 0, ""));
        assertTrue(line.toLowerCase(java.util.Locale.ROOT).contains("verif"), line);
    }

    @Test
    void quarantineExplainsItself() {
        String line = Formats.progressLine(
                new ProgressSnapshot(DownloadState.QUARANTINED, 5, 100, 0, 0, "Checksum mismatch"));
        assertTrue(line.contains("Quarantined"), line);
        assertTrue(line.contains("Checksum mismatch"), line);
    }

    @Test
    void progressLineNamesTheProtocolAndItsOwnSourceNoun() {
        ProgressSnapshot downloading = new ProgressSnapshot(DownloadState.DOWNLOADING, 500, 1000, 100, 4, "");
        String http = Formats.progressLine(downloading, "HTTP", "mirrors");
        assertTrue(http.contains("via HTTP"), http);
        assertTrue(http.contains("4 mirrors"), http);

        String torrent = Formats.progressLine(downloading, "BitTorrent", "peers");
        assertTrue(torrent.contains("via BitTorrent"), torrent);
        assertTrue(torrent.contains("4 peers"), torrent);

        // Connecting says it too — that is when a user is asking "which one did it pick?"
        ProgressSnapshot connecting = new ProgressSnapshot(DownloadState.CONNECTING, 0, 0, 0, 0, "");
        assertTrue(Formats.progressLine(connecting, "BitTorrent", "peers").contains("via BitTorrent"));
    }

    @Test
    void progressLineOmitsTheProtocolBeforeOneIsChosen() {
        ProgressSnapshot downloading = new ProgressSnapshot(DownloadState.DOWNLOADING, 500, 1000, 100, 2, "");
        String line = Formats.progressLine(downloading, "", "sources");
        assertFalse(line.contains("via"), line);
        assertTrue(line.contains("2 sources"), line);
        assertEquals(line, Formats.progressLine(downloading), "the one-arg overload is the unlabelled form");
    }
}
