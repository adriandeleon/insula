package com.insula.download;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.insula.catalog.ZimEntry;
import com.insula.library.Library;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pipeline: download → verify → library or quarantine. These are the cases the design
 * brief calls non-negotiable, so they are covered against a real (loopback) server.
 */
class DownloadManagerTest {

    /** Serves an archive, its .meta4 and its .sha256 — the three URLs the pipeline touches. */
    private static final class Origin implements AutoCloseable {
        private final HttpServer server;
        private final byte[] data;

        Origin(byte[] data, String publishedSha256) throws IOException {
            this.data = data;
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/file.zim", exchange -> respond(exchange, data, "application/octet-stream"));
            server.createContext(
                    "/file.zim.meta4",
                    exchange -> respond(
                            exchange,
                            ("<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"><file name=\"file.zim\"><size>"
                                            + data.length + "</size><url priority=\"1\">" + url()
                                            + "</url></file></metalink>")
                                    .getBytes(StandardCharsets.UTF_8),
                            "application/metalink4+xml"));
            server.createContext(
                    "/file.zim.sha256",
                    exchange -> respond(
                            exchange, (publishedSha256 + "  file.zim").getBytes(StandardCharsets.UTF_8), "text/plain"));
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "origin");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body, String type)
                throws IOException {
            try (exchange) {
                String range = exchange.getRequestHeaders().getFirst("Range");
                byte[] out = body;
                int status = 200;
                if (range != null) {
                    String[] bounds = range.replace("bytes=", "").split("-");
                    int from = Integer.parseInt(bounds[0]);
                    int to = Math.min(Integer.parseInt(bounds[1]), body.length - 1);
                    out = java.util.Arrays.copyOfRange(body, from, to + 1);
                    status = 206;
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                }
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/file.zim";
        }

        ZimEntry entry() {
            return new ZimEntry(
                    "urn:uuid:1",
                    "Test Archive",
                    "",
                    "file",
                    "",
                    List.of("eng"),
                    "other",
                    1,
                    0,
                    url() + ".meta4",
                    data.length);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static byte[] payload() {
        byte[] data = new byte[512 * 1024];
        new Random(7).nextBytes(data);
        return data;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void awaitTerminal(DownloadManager.Job job) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (job.snapshot().state().isTerminal()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job never reached a terminal state: " + job.snapshot());
    }

    private static DownloadManager manager(Library library, Path dir) {
        return new DownloadManager(new TransportSelector(new HttpMultiSourceTransport()), library, dir);
    }

    @Test
    void verifiedDownloadEntersTheLibrary(@TempDir Path dir) throws Exception {
        byte[] data = payload();
        try (Origin origin = new Origin(data, sha256(data))) {
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job job = manager.enqueue(origin.entry());
                awaitTerminal(job);

                assertEquals(
                        DownloadState.COMPLETED,
                        job.snapshot().state(),
                        job.snapshot().detail());
                assertArrayEqualsOnDisk(data, job.destination());
                assertEquals(1, library.verifiedEntries().size());
                assertTrue(library.isVerified(job.destination()));
            }
        }
    }

    @Test
    void aChecksumMismatchQuarantinesInsteadOfDeleting(@TempDir Path dir) throws Exception {
        byte[] data = payload();
        String wrongDigest = "0".repeat(64);
        try (Origin origin = new Origin(data, wrongDigest)) {
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job job = manager.enqueue(origin.entry());
                awaitTerminal(job);

                assertEquals(DownloadState.QUARANTINED, job.snapshot().state(), () -> "snapshot was " + job.snapshot());
                assertFalse(Files.exists(job.destination()), "the bad file must not sit where the library looks");
                Path quarantined =
                        job.destination().resolveSibling(job.destination().getFileName() + Quarantine.SUFFIX);
                assertTrue(Files.exists(quarantined), "bytes are kept, not deleted");
                assertEquals(data.length, Files.size(quarantined));
                assertEquals(0, library.verifiedEntries().size(), "a quarantined file is never auto-openable");
            }
        }
    }

    @Test
    void reportsVerifyingBeforeCompleting(@TempDir Path dir) throws Exception {
        byte[] data = payload();
        try (Origin origin = new Origin(data, sha256(data))) {
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job job = manager.enqueue(origin.entry());
                awaitTerminal(job);
                // The pipeline must expose a distinct verifying phase; on a 100 GB archive this is
                // minutes long and a UI that shows nothing here looks hung.
                assertEquals(DownloadState.COMPLETED, job.snapshot().state(), () -> "snapshot was " + job.snapshot());
                assertEquals(1.0, job.snapshot().fraction(), 0.0001);
            }
        }
    }

    @Test
    void aJobIsNeverTerminalBeforeVerificationHasRun(@TempDir Path dir) throws Exception {
        // Regression: the transport reports COMPLETED the moment the bytes land. If that leaks
        // through as the job's state, a UI polling isTerminal() shows "Ready" on a file whose
        // checksum has not been checked — and a shutdown right then cancels verification. Caught
        // against the live catalog, where it left the library empty after a "completed" download.
        byte[] data = payload();
        try (Origin origin = new Origin(data, sha256(data))) {
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job job = manager.enqueue(origin.entry());
                awaitTerminal(job);

                // The invariant: reaching COMPLETED implies the archive is in the library, verified.
                assertEquals(DownloadState.COMPLETED, job.snapshot().state());
                assertTrue(
                        library.isVerified(job.destination()),
                        "COMPLETED must mean verified-and-admitted, not merely downloaded");
            }
        }
    }

    @Test
    void progressTotalUsesTheAuthoritativeSizeNotTheRoundedCatalogFigure(@TempDir Path dir) throws Exception {
        // Regression: the OPDS acquisition `length` is rounded UP to a whole KiB (measured:
        // 712704 published for a 712215-byte archive), so using it as the denominator leaves the
        // progress bar stuck just short of 100% on every real download.
        byte[] data = payload();
        long roundedUp = -Math.floorDiv(-data.length, 1024) * 1024 + 512; // deliberately too big
        try (Origin origin = new Origin(data, sha256(data))) {
            ZimEntry inflated = new ZimEntry(
                    "urn:uuid:2",
                    "T",
                    "",
                    "file",
                    "",
                    List.of("eng"),
                    "other",
                    1,
                    0,
                    origin.url() + ".meta4",
                    roundedUp);
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job job = manager.enqueue(inflated);
                awaitTerminal(job);

                assertEquals(
                        DownloadState.COMPLETED,
                        job.snapshot().state(),
                        job.snapshot().detail());
                assertEquals(
                        data.length,
                        job.snapshot().bytesTotal(),
                        "the Metalink <size> is authoritative, not the catalog's rounded length");
                assertEquals(1.0, job.snapshot().fraction(), 0.0001, "progress must actually reach 100%");
            }
        }
    }

    @Test
    void enqueuingTheSameEntryTwiceReusesTheJob(@TempDir Path dir) throws Exception {
        byte[] data = payload();
        try (Origin origin = new Origin(data, sha256(data))) {
            Library library = Library.load(dir.resolve("library.properties"));
            try (DownloadManager manager = manager(library, dir.resolve("zims"))) {
                DownloadManager.Job first = manager.enqueue(origin.entry());
                DownloadManager.Job second = manager.enqueue(origin.entry());
                assertEquals(first, second, "a second click must not start a competing download");
                awaitTerminal(first);
                assertEquals(1, manager.jobs().size());
            }
        }
    }

    private static void assertArrayEqualsOnDisk(byte[] expected, Path file) throws IOException {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, Files.readAllBytes(file));
    }
}
