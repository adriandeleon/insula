package com.insula.download;

import java.nio.file.Path;
import java.util.List;

import com.insula.catalog.ZimEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gating and configuration of the torrent transport. The transfer itself needs a live swarm, so
 * it is exercised manually against download.kiwix.org rather than in CI — what is pinned here is
 * that the transport can never become mandatory or fire when it cannot work.
 */
class TorrentTransportTest {

    private static ZimEntry entry(String metalinkUrl, long size) {
        return new ZimEntry("urn:uuid:t", "T", "", "file", "", List.of("eng"), "wikipedia", 1, 0, metalinkUrl, size);
    }

    @Test
    void reportsWhetherTheNativeLibraryLoaded() {
        // Must answer rather than throw on a platform with no native: the selector calls this to
        // decide whether to register the transport at all.
        boolean available = TorrentTransport.isAvailable();
        assertEquals(available, TorrentTransport.isAvailable(), "the answer must be stable");
    }

    @Test
    void declinesAnEntryWithNoTorrentSidecar() {
        TorrentTransport transport = new TorrentTransport();
        assertFalse(transport.canHandle(null));
        assertFalse(transport.canHandle(entry("", 100)), "no URL means no .torrent to fetch");
    }

    @Test
    void acceptsAnEntryOnlyWhenTheNativeIsPresent() {
        ZimEntry e = entry("https://example.org/zim/a.zim.meta4", 100);
        assertEquals(
                TorrentTransport.isAvailable(),
                new TorrentTransport().canHandle(e),
                "canHandle must track native availability, or the selector picks a transport that cannot run");
    }

    @Test
    void httpRemainsTheFallbackEvenWithTorrentRegistered() {
        // The load-bearing guarantee: BitTorrent is an option, never the only way out.
        DownloadTransport http = new HttpMultiSourceTransport();
        TransportSelector selector = new TransportSelector(http);
        selector.register(new TorrentTransport());
        assertEquals(http, selector.fallbackFor(entry("https://example.org/a.zim.meta4", Long.MAX_VALUE)));
    }

    @Test
    void smallArchivesStayOnHttpEvenWhenTorrentIsAvailable() {
        TransportSelector selector = new TransportSelector(new HttpMultiSourceTransport());
        selector.register(new TorrentTransport());
        assertEquals(
                HttpMultiSourceTransport.ID,
                selector.select(entry("https://example.org/a.zim.meta4", 10L * 1024 * 1024))
                        .id(),
                "torrent overhead is not worth it below the threshold");
    }

    @Test
    void aFailedStartStillSettlesTheFuture(@TempDir Path dir) throws Exception {
        // A transport that neither completes nor fails would hang the pipeline forever.
        ZimEntry unreachable = entry("http://127.0.0.1:1/nothing.zim.meta4", 1000);
        DownloadHandle handle = new TorrentTransport().start(unreachable, dir.resolve("x.zim"), ProgressListener.NONE);
        DownloadResult result = handle.completion().get(90, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(result.ok());
        assertTrue(result.state().isTerminal());
    }

    @Test
    void seedingAndTorrentAreBothOffByDefault(@TempDir Path dir) {
        // Silently uploading tens of gigabytes on a metered connection is not an opt-out default,
        // and BitTorrent is blocked on many of the networks this content is for.
        var settings = com.insula.config.Settings.load(dir.resolve("fresh.properties"));
        assertFalse(settings.isSeedingEnabled());
        assertFalse(settings.isTorrentEnabled());
    }
}
