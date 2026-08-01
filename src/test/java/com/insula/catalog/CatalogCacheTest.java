package com.insula.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cache against a loopback catalog server with real ETag/304 behaviour. */
class CatalogCacheTest {

    private static final String FEED = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>A</title><name>a</name><language>eng</language>
                <updated>2026-07-01T00:00:00Z</updated>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      href="https://example.org/a.zim.meta4" length="10"/></entry>
            </feed>
            """;

    /** Serves a feed with an ETag; honours If-None-Match with a 304. */
    private static final class CatalogServer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger fullResponses = new AtomicInteger();
        final AtomicInteger notModified = new AtomicInteger();
        volatile String body = FEED;
        volatile String etag = "\"v1\"";
        volatile int forcedStatus = 0;

        CatalogServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v2/entries", exchange -> {
                try (exchange) {
                    if (forcedStatus != 0) {
                        exchange.sendResponseHeaders(forcedStatus, -1);
                        return;
                    }
                    String match = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (etag.equals(match)) {
                        notModified.incrementAndGet();
                        exchange.sendResponseHeaders(304, -1);
                        return;
                    }
                    fullResponses.incrementAndGet();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("ETag", etag);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            });
            server.start();
        }

        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/entries";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void firstRefreshWritesTheCacheAtomically(@TempDir Path dir) throws Exception {
        try (CatalogServer origin = new CatalogServer();
                CatalogCache cache = new CatalogCache(dir.resolve("catalog"), origin.endpoint())) {
            assertTrue(cache.load().isEmpty(), "no cache before the first fetch");

            CatalogCache.RefreshOutcome outcome = cache.refreshNow();
            assertTrue(outcome.ok(), outcome.error());
            assertTrue(outcome.updated());

            assertEquals(1, cache.load().orElseThrow().entries().size());
            assertTrue(cache.fetchedAtMillis() > 0);
            assertFalse(cache.isOlderThan(Duration.ofDays(7), System.currentTimeMillis()));
        }
    }

    @Test
    void anUnchangedFeedCostsOne304AndTouchesFreshness(@TempDir Path dir) throws Exception {
        try (CatalogServer origin = new CatalogServer();
                CatalogCache cache = new CatalogCache(dir.resolve("catalog"), origin.endpoint())) {
            assertTrue(cache.refreshNow().updated());
            long firstFetch = cache.fetchedAtMillis();

            Thread.sleep(10);
            CatalogCache.RefreshOutcome second = cache.refreshNow();
            assertTrue(second.ok());
            assertTrue(second.unchanged(), "matching ETag should revalidate, not re-download");
            assertEquals(1, origin.fullResponses.get(), "the body must be sent exactly once");
            assertEquals(1, origin.notModified.get());
            assertTrue(cache.fetchedAtMillis() > firstFetch, "a 304 still confirms freshness");
        }
    }

    @Test
    void aFailedRefreshLeavesTheOldCacheUntouched(@TempDir Path dir) throws Exception {
        try (CatalogServer origin = new CatalogServer();
                CatalogCache cache = new CatalogCache(dir.resolve("catalog"), origin.endpoint())) {
            assertTrue(cache.refreshNow().updated());

            origin.forcedStatus = 503;
            CatalogCache.RefreshOutcome outcome = cache.refreshNow();
            assertFalse(outcome.ok());
            assertEquals(1, cache.load().orElseThrow().entries().size(), "old cache must survive a failure");
        }
    }

    @Test
    void aMalformedPayloadNeverReplacesAGoodCache(@TempDir Path dir) throws Exception {
        try (CatalogServer origin = new CatalogServer();
                CatalogCache cache = new CatalogCache(dir.resolve("catalog"), origin.endpoint())) {
            assertTrue(cache.refreshNow().updated());

            origin.body = "<feed this is not xml";
            origin.etag = "\"v2\""; // force a full response
            CatalogCache.RefreshOutcome outcome = cache.refreshNow();
            assertFalse(outcome.ok(), "garbage must be rejected");
            assertEquals(1, cache.load().orElseThrow().entries().size(), "the store must never get emptier");
        }
    }

    @Test
    void anEmptyFeedIsRejectedAsSuspect(@TempDir Path dir) throws Exception {
        try (CatalogServer origin = new CatalogServer();
                CatalogCache cache = new CatalogCache(dir.resolve("catalog"), origin.endpoint())) {
            assertTrue(cache.refreshNow().updated());
            origin.body = "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>";
            origin.etag = "\"v3\"";
            assertFalse(cache.refreshNow().ok(), "an empty catalog is a server problem, not a state to adopt");
            assertEquals(1, cache.load().orElseThrow().entries().size());
        }
    }

    @Test
    void freshnessThresholdsMatchTheSpec(@TempDir Path dir) {
        try (CatalogCache cache = new CatalogCache(dir.resolve("catalog"), "http://127.0.0.1:1/v2/entries")) {
            long now = System.currentTimeMillis();
            assertTrue(cache.isOlderThan(CatalogCache.AUTO_REFRESH_AGE, now), "never fetched counts as stale");
            assertEquals(Duration.ofDays(7), CatalogCache.AUTO_REFRESH_AGE);
            assertEquals(Duration.ofDays(14), CatalogCache.STALE_AGE);
        }
    }

    @Test
    void resolvesRelativeIllustrationHrefsAgainstTheCatalogHost(@TempDir Path dir) {
        try (CatalogCache cache =
                new CatalogCache(dir.resolve("catalog"), "https://opds.library.kiwix.org/v2/entries")) {
            assertEquals(
                    "https://opds.library.kiwix.org/catalog/v2/illustration/abc/?size=48",
                    cache.resolveHref("/catalog/v2/illustration/abc/?size=48"));
            assertEquals("", cache.resolveHref(""));
            assertEquals("https://elsewhere.example/x.png", cache.resolveHref("https://elsewhere.example/x.png"));
        }
    }

    @Test
    void offlineWithNoCacheFailsCleanly(@TempDir Path dir) throws IOException {
        try (CatalogCache cache = new CatalogCache(dir.resolve("catalog"), "http://127.0.0.1:1/v2/entries")) {
            CatalogCache.RefreshOutcome outcome = cache.refreshNow();
            assertFalse(outcome.ok());
            assertTrue(cache.load().isEmpty());
            assertTrue(Files.notExists(dir.resolve("catalog/entries.xml")));
        }
    }
}
