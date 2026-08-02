package com.insula.download;

import java.util.List;

import com.insula.catalog.ZimEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which transport an archive gets, and where the size line sits. */
class TorrentThresholdTest {

    private static final long GB = 1024L * 1024 * 1024;

    private static ZimEntry entryOf(long sizeBytes) {
        return new ZimEntry(
                "urn:uuid:x",
                "Archive",
                "",
                "archive",
                "",
                List.of("eng"),
                "other",
                1,
                0,
                "https://example.invalid/archive.zim.meta4",
                sizeBytes);
    }

    /** A transport that will take anything, standing in for the torrent one. */
    private static DownloadTransport willingTransport() {
        return new DownloadTransport() {
            @Override
            public String id() {
                return "torrent";
            }

            @Override
            public boolean canHandle(ZimEntry entry) {
                return true;
            }

            @Override
            public DownloadHandle start(ZimEntry entry, java.nio.file.Path destination, ProgressListener listener) {
                throw new UnsupportedOperationException("not started in this test");
            }
        };
    }

    private static DownloadTransport httpStub() {
        return new DownloadTransport() {
            @Override
            public String id() {
                return "http";
            }

            @Override
            public boolean canHandle(ZimEntry entry) {
                return true;
            }

            @Override
            public DownloadHandle start(ZimEntry entry, java.nio.file.Path destination, ProgressListener listener) {
                throw new UnsupportedOperationException("not started in this test");
            }
        };
    }

    @Test
    void theDefaultLineIsOneGigabyte() {
        assertEquals(GB, TransportSelector.DEFAULT_TORRENT_THRESHOLD);
    }

    @Test
    void anArchiveAtOrOverTheThresholdPrefersTheTorrent() {
        DownloadTransport http = httpStub();
        DownloadTransport torrent = willingTransport();
        TransportSelector selector = new TransportSelector(http);
        selector.register(torrent);

        assertSame(torrent, selector.select(entryOf(GB)), "exactly at the line counts as over it");
        assertSame(torrent, selector.select(entryOf(4 * GB)));
        assertSame(http, selector.select(entryOf(GB - 1)), "and just under it does not");
    }

    @Test
    void raisingTheThresholdSendsMidSizedArchivesBackToHttp() {
        DownloadTransport http = httpStub();
        DownloadTransport torrent = willingTransport();
        TransportSelector selector = new TransportSelector(http);
        selector.register(torrent);

        selector.setTorrentThreshold(10 * GB);
        assertSame(http, selector.select(entryOf(4 * GB)));
        assertSame(torrent, selector.select(entryOf(11 * GB)));
    }

    @Test
    void aZeroThresholdMeansEveryArchivePrefersTheTorrent() {
        DownloadTransport http = httpStub();
        DownloadTransport torrent = willingTransport();
        TransportSelector selector = new TransportSelector(http);
        selector.register(torrent);

        selector.setTorrentThreshold(0);
        assertSame(torrent, selector.select(entryOf(1024)));
    }

    @Test
    void httpRemainsTheFallbackWhateverTheThreshold() {
        // The guarantee the ordering exists for: a network with BitTorrent blocked still downloads.
        DownloadTransport http = httpStub();
        TransportSelector selector = new TransportSelector(http);
        selector.setTorrentThreshold(0);
        assertSame(http, selector.select(entryOf(100 * GB)), "no torrent transport registered");

        selector.register(willingTransport());
        selector.setTorrentEnabled(false);
        assertSame(http, selector.select(entryOf(100 * GB)), "or the user turned it off");
    }
}
