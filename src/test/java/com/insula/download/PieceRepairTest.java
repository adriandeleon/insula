package com.insula.download;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Piece-level repair against a loopback mirror: only the damaged ranges travel, and the whole-file
 * SHA-256 keeps the final say.
 */
class PieceRepairTest {

    private static final int PIECE = 1024;

    private byte[] original;
    private Metalink metalink;
    private HttpServer server;
    private final List<String> servedRanges = new ArrayList<>();

    @BeforeEach
    void startMirror() throws Exception {
        original = new byte[3 * PIECE + 137]; // 4 pieces, last one short
        new Random(42).nextBytes(original);

        List<String> pieceHashes = new ArrayList<>();
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (int off = 0; off < original.length; off += PIECE) {
            int len = Math.min(PIECE, original.length - off);
            sha1.reset();
            sha1.update(original, off, len);
            pieceHashes.add(HexFormat.of().formatHex(sha1.digest()));
        }
        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file.zim", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            servedRanges.add(range);
            if (range == null || !range.startsWith("bytes=")) {
                exchange.sendResponseHeaders(200, original.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(original);
                }
                return;
            }
            String[] parts = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(parts[0]);
            int to = Integer.parseInt(parts[1]);
            byte[] slice = java.util.Arrays.copyOfRange(original, from, to + 1);
            exchange.sendResponseHeaders(206, slice.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(slice);
            }
        });
        server.start();

        String mirror = "http://127.0.0.1:" + server.getAddress().getPort() + "/file.zim";
        metalink = new Metalink("file.zim", original.length, sha256, "", List.of(mirror), PIECE, "sha-1", pieceHashes);
    }

    @AfterEach
    void stopMirror() {
        server.stop(0);
    }

    private Path corruptCopy(Path dir) throws IOException {
        byte[] damaged = original.clone();
        damaged[PIECE + 10] ^= 0x55; // flip a bit inside piece 1
        Path file = dir.resolve("file.zim.corrupt");
        Files.write(file, damaged);
        return file;
    }

    @Test
    void scanPinpointsTheDamagedPiece(@TempDir Path dir) throws Exception {
        List<PieceRepair.BadPiece> bad = PieceRepair.scan(corruptCopy(dir), metalink, null);
        assertEquals(1, bad.size());
        assertEquals(1, bad.getFirst().index());
        assertEquals(PIECE, bad.getFirst().offset());
    }

    @Test
    void aTruncatedFileReportsItsMissingTailPieces(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("file.zim.corrupt");
        Files.write(file, java.util.Arrays.copyOfRange(original, 0, PIECE + 5)); // half of piece 1
        List<PieceRepair.BadPiece> bad = PieceRepair.scan(file, metalink, null);
        assertEquals(
                List.of(1, 2, 3), bad.stream().map(PieceRepair.BadPiece::index).toList());
    }

    @Test
    void repairRefetchesOnlyTheBadRangeAndRestoresTheExactBytes(@TempDir Path dir) throws Exception {
        Path file = corruptCopy(dir);
        PieceRepair.Result result = PieceRepair.repair(file, metalink, HttpClient.newHttpClient(), null);
        assertTrue(result.ok(), result.message());
        assertEquals(1, result.repairedPieces());
        assertArrayEquals(original, Files.readAllBytes(file), "the repaired file is byte-identical");
        assertEquals(metalink.sha256(), result.sha256());
        assertEquals(
                List.of("bytes=" + PIECE + "-" + (2 * PIECE - 1)),
                servedRanges,
                "exactly one range request, for exactly the damaged piece");
    }

    @Test
    void repairFixesTruncationByFetchingTheTail(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("file.zim.corrupt");
        Files.write(file, java.util.Arrays.copyOfRange(original, 0, PIECE));
        PieceRepair.Result result = PieceRepair.repair(file, metalink, HttpClient.newHttpClient(), null);
        assertTrue(result.ok(), result.message());
        assertEquals(3, result.repairedPieces());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void aMirrorServingGarbageFailsTheRepairWithoutMakingThingsWorse(@TempDir Path dir) throws Exception {
        server.removeContext("/file.zim");
        server.createContext("/file.zim", exchange -> {
            byte[] junk = new byte[PIECE];
            exchange.sendResponseHeaders(206, junk.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(junk);
            }
        });
        Path file = corruptCopy(dir);
        byte[] before = Files.readAllBytes(file);
        PieceRepair.Result result = PieceRepair.repair(file, metalink, HttpClient.newHttpClient(), null);
        assertFalse(result.ok());
        assertArrayEquals(before, Files.readAllBytes(file), "junk bytes are never written");
    }

    @Test
    void repairWithoutPieceHashesRefusesHonestly(@TempDir Path dir) throws Exception {
        Metalink noPieces =
                new Metalink("file.zim", original.length, metalink.sha256(), "", metalink.mirrors(), 0, "", List.of());
        PieceRepair.Result result = PieceRepair.repair(corruptCopy(dir), noPieces, HttpClient.newHttpClient(), null);
        assertFalse(result.ok());
        assertTrue(result.message().contains("piece hashes"));
    }
}
