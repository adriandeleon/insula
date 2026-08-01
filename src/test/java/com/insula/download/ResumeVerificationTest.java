package com.insula.download;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.insula.catalog.ZimEntry;
import com.insula.library.Library;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quit-during-verify leaves a bare {@code .zim} plus a recovery sidecar and no library row.
 * The next launch must finish the job: fetch the Metalink, verify, admit — or quarantine.
 */
class ResumeVerificationTest {

    private HttpServer server;
    private String metalinkUrl;
    private byte[] bytes;
    private String sha256;

    @BeforeEach
    void startServer() throws Exception {
        bytes = "pretend zim content".getBytes(StandardCharsets.UTF_8);
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x.zim.meta4", exchange -> {
            byte[] xml = meta4(sha256, bytes.length).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(xml);
            }
        });
        server.start();
        metalinkUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/x.zim.meta4";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String meta4(String sha256, long size) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="x.zim">
                    <size>%d</size>
                    <hash type="sha-256">%s</hash>
                    <url priority="1">http://127.0.0.1:1/x.zim</url>
                  </file>
                </metalink>
                """.formatted(size, sha256);
    }

    private DownloadManager manager(Path dir, Library library) {
        return new DownloadManager(
                new TransportSelector(new HttpMultiSourceTransport()), library, dir.resolve("archives"));
    }

    private ZimEntry entry() {
        return new ZimEntry("id-x", "X", "", "x", "", List.of("eng"), "other", 1, 0, metalinkUrl, bytes.length);
    }

    @Test
    void anUnverifiedLeftoverIsVerifiedAndAdmittedAtStartup(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("x.zim");
        Files.write(zim, bytes);
        RecoverySidecar.write(zim, entry());

        Library library = Library.load(dir.resolve("library.properties"));
        try (DownloadManager manager = manager(dir, library)) {
            assertEquals(List.of(zim), manager.pendingVerifications());
            manager.resumePendingVerification(msg -> {});
            awaitLibrarySize(library, 1);
        }
        assertTrue(library.isVerified(zim), "the leftover is admitted verified");
        assertEquals(sha256, library.find(zim).orElseThrow().sha256());
        assertFalse(Files.exists(RecoverySidecar.sidecarFor(zim)), "the sidecar's job is done once the row exists");
    }

    @Test
    void aCorruptLeftoverIsQuarantinedNotAdmitted(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("x.zim");
        Files.write(zim, "different bytes entirely".getBytes(StandardCharsets.UTF_8));
        RecoverySidecar.write(zim, entry());

        Library library = Library.load(dir.resolve("library.properties"));
        try (DownloadManager manager = manager(dir, library)) {
            manager.resumePendingVerification(msg -> {});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (Files.exists(zim) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
        assertFalse(Files.exists(zim), "the bad file must not keep its .zim name");
        assertTrue(Files.exists(archives.resolve("x.zim" + Quarantine.SUFFIX)));
        assertEquals(0, library.size());
        assertTrue(
                Files.exists(RecoverySidecar.sidecarFor(zim)),
                "the sidecar stays with a quarantined file so Repair remains possible");
    }

    @Test
    void anInFlightPartIsNotTreatedAsPending(@TempDir Path dir) throws Exception {
        Path archives = dir.resolve("archives");
        Files.createDirectories(archives);
        Path zim = archives.resolve("x.zim");
        Files.write(zim, bytes);
        Files.write(archives.resolve("x.zim.part"), new byte[10]);
        RecoverySidecar.write(zim, entry());

        Library library = Library.load(dir.resolve("library.properties"));
        try (DownloadManager manager = manager(dir, library)) {
            assertTrue(manager.pendingVerifications().isEmpty());
        }
    }

    private static void awaitLibrarySize(Library library, int size) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            synchronized (library) {
                if (library.size() >= size) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("library never reached size " + size);
    }
}
