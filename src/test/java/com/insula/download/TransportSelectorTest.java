package com.insula.download;

import java.nio.file.Path;
import java.util.List;

import com.insula.catalog.ZimEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportSelectorTest {

    private static ZimEntry entryOfSize(long size) {
        return new ZimEntry(
                "id", "T", "", "n", "", List.of("eng"), "other", 1, 0, "https://example.org/a.zim.meta4", size);
    }

    /** A stand-in for the future torrent transport. */
    private record StubTransport(String id, boolean handles) implements DownloadTransport {
        @Override
        public boolean canHandle(ZimEntry entry) {
            return handles;
        }

        @Override
        public DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener) {
            throw new UnsupportedOperationException();
        }
    }

    private static final DownloadTransport HTTP = new StubTransport("http-multisource", true);
    private static final DownloadTransport TORRENT = new StubTransport("torrent", true);

    @Test
    void smallFilesAlwaysUseHttp() {
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(TORRENT);
        assertSame(HTTP, selector.select(entryOfSize(100L * 1024 * 1024)));
        assertFalse(selector.prefersAlternate(entryOfSize(100L * 1024 * 1024)));
    }

    @Test
    void largeFilesPreferTheAlternateTransport() {
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(TORRENT);
        assertSame(TORRENT, selector.select(entryOfSize(20L * 1024 * 1024 * 1024)));
    }

    @Test
    void withNoAlternateRegisteredEverythingUsesHttp() {
        // The shipping state today: BitTorrent is designed for but not yet implemented.
        TransportSelector selector = new TransportSelector(HTTP);
        assertSame(HTTP, selector.select(entryOfSize(50L * 1024 * 1024 * 1024)));
    }

    @Test
    void disablingTorrentForcesHttpRegardlessOfSize() {
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(TORRENT);
        selector.setTorrentEnabled(false);
        assertSame(HTTP, selector.select(entryOfSize(50L * 1024 * 1024 * 1024)));
    }

    @Test
    void anAlternateThatCannotHandleTheEntryIsSkipped() {
        // e.g. no .torrent sidecar, or BitTorrent blocked on this network.
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(new StubTransport("torrent", false));
        assertSame(HTTP, selector.select(entryOfSize(50L * 1024 * 1024 * 1024)));
    }

    @Test
    void thresholdIsConfigurable() {
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(TORRENT);
        selector.setTorrentThreshold(1024);
        assertSame(TORRENT, selector.select(entryOfSize(2048)));
    }

    @Test
    void theFallbackIsAlwaysHttp() {
        TransportSelector selector = new TransportSelector(HTTP);
        selector.register(TORRENT);
        assertSame(HTTP, selector.fallbackFor(entryOfSize(50L * 1024 * 1024 * 1024)));
        assertTrue(selector.prefersAlternate(entryOfSize(50L * 1024 * 1024 * 1024)));
    }
}
