package com.offlinewiki.download;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.offlinewiki.catalog.ZimEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real transport against loopback mirrors — no network. These cover the cases that
 * corrupt a file rather than merely slow it down: chunk reassembly, resume, and what happens when
 * a mirror lies.
 */
class HttpMultiSourceTransportTest {

    /** Small enough to stay fast, large enough to span several pieces and chunks. */
    private static final int PIECE_LENGTH = 64 * 1024;

    private static final int FILE_SIZE = PIECE_LENGTH * 9 + 1234; // deliberately not piece-aligned

    private static byte[] content() {
        byte[] data = new byte[FILE_SIZE];
        new Random(42).nextBytes(data);
        return data;
    }

    private static List<String> pieceHashes(byte[] data) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        List<String> hashes = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += PIECE_LENGTH) {
            sha1.reset();
            sha1.update(data, offset, Math.min(PIECE_LENGTH, data.length - offset));
            hashes.add(HexFormat.of().formatHex(sha1.digest()));
        }
        return hashes;
    }

    private static String metalink(byte[] data, List<String> mirrorUrls, boolean withPieces) throws Exception {
        StringBuilder sb =
                new StringBuilder("<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"><file name=\"file.zim\">");
        sb.append("<size>").append(data.length).append("</size>");
        int priority = 1;
        for (String url : mirrorUrls) {
            sb.append("<url priority=\"")
                    .append(priority++)
                    .append("\">")
                    .append(url)
                    .append("</url>");
        }
        if (withPieces) {
            sb.append("<pieces length=\"").append(PIECE_LENGTH).append("\" type=\"sha-1\">");
            for (String hash : pieceHashes(data)) {
                sb.append("<hash>").append(hash).append("</hash>");
            }
            sb.append("</pieces>");
        }
        return sb.append("</file></metalink>").toString();
    }

    private static ZimEntry entry(String metalinkUrl, long size) {
        return new ZimEntry("urn:uuid:test", "Test", "", "file", "", List.of("eng"), "other", 1, 0, metalinkUrl, size);
    }

    private static DownloadResult await(DownloadHandle handle) throws Exception {
        return handle.completion().get(60, TimeUnit.SECONDS);
    }

    @Test
    void downloadsAndReassemblesAcrossMirrors(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror a = new FakeMirror(data, FakeMirror.Behaviour.NORMAL);
                FakeMirror b = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            // Serve a metalink naming both real mirrors.
            String meta = metalink(data, List.of(a.url(), b.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));

                assertTrue(result.ok(), result.message());
                assertArrayEquals(data, Files.readAllBytes(dest), "reassembled file must match byte for byte");
                assertTrue(a.requestCount() + b.requestCount() >= 2, "work should span mirrors");
            }
        }
    }

    @Test
    void rejectsChunksThatFailPieceVerification(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror bad = new FakeMirror(data, FakeMirror.Behaviour.CORRUPTS)) {
            String meta = metalink(data, List.of(bad.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));

                assertFalse(result.ok(), "a mirror serving corrupt bytes must not produce a 'successful' download");
                assertEquals(DownloadState.FAILED, result.state());
            }
        }
    }

    @Test
    void withoutPieceHashesTheSameCorruptionGoesUndetected(@TempDir Path dir) throws Exception {
        // The contrast that proves the previous test fails *because of* piece verification:
        // identical corrupt mirror, but a sidecar with no <pieces>, and the bytes land happily.
        // This is exactly the gap the final whole-file SHA-256 exists to close.
        byte[] data = content();
        try (FakeMirror bad = new FakeMirror(data, FakeMirror.Behaviour.CORRUPTS)) {
            String meta = metalink(data, List.of(bad.url()), false);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));

                assertTrue(result.ok(), "no piece hashes → the transport cannot notice");
                assertFalse(java.util.Arrays.equals(data, Files.readAllBytes(dest)), "the file really is corrupt");
            }
        }
    }

    @Test
    void recoversWhenOneMirrorIsBroken(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror broken = new FakeMirror(data, FakeMirror.Behaviour.ERRORS);
                FakeMirror good = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(broken.url(), good.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));

                assertTrue(result.ok(), "a dead mirror should be retried elsewhere: " + result.message());
                assertArrayEquals(data, Files.readAllBytes(dest));
            }
        }
    }

    @Test
    void resumesFromAPartialDownload(@TempDir Path dir) throws Exception {
        byte[] data = content();
        Path dest = dir.resolve("file.zim");

        // Simulate an interrupted run: full-length file, only the first chunk marked done.
        ChunkPlan plan =
                ChunkPlan.forFile(data.length, PIECE_LENGTH, pieceHashes(data).size());
        Files.write(dest, new byte[data.length]);
        ChunkPlan.Chunk first = plan.chunks().getFirst();
        try (var channel = java.nio.channels.FileChannel.open(dest, java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(data, 0, (int) first.length()), first.start());
        }
        plan.markDone(0);
        Files.writeString(
                dest.resolveSibling(dest.getFileName() + HttpMultiSourceTransport.PART_SUFFIX),
                plan.toBitmap(),
                StandardCharsets.US_ASCII);

        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(mirror.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));

                assertTrue(result.ok(), result.message());
                assertArrayEquals(data, Files.readAllBytes(dest));
                assertEquals(
                        plan.size() - 1, mirror.requestCount(), "the already-complete chunk must not be re-fetched");
            }
        }
    }

    @Test
    void deletesResumeStateOnceComplete(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(mirror.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                assertTrue(await(new HttpMultiSourceTransport()
                                .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE))
                        .ok());
                assertFalse(
                        Files.exists(dest.resolveSibling(dest.getFileName() + HttpMultiSourceTransport.PART_SUFFIX)),
                        "a finished download should leave no .part file behind");
            }
        }
    }

    @Test
    void worksWithoutPieceHashes(@TempDir Path dir) throws Exception {
        // .meta4 without <pieces>: chunking still works, only per-chunk verification is lost.
        byte[] data = content();
        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(mirror.url()), false);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadResult result = await(new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE));
                assertTrue(result.ok(), result.message());
                assertArrayEquals(data, Files.readAllBytes(dest));
            }
        }
    }

    @Test
    void reportsProgressAndEndsAtOneHundredPercent(@TempDir Path dir) throws Exception {
        byte[] data = content();
        List<ProgressSnapshot> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(mirror.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadHandle handle = new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, seen::add);
                assertTrue(await(handle).ok());

                assertFalse(seen.isEmpty(), "the transport must report progress");
                assertEquals(data.length, handle.snapshot().bytesCompleted());
                assertEquals(1.0, handle.snapshot().fraction(), 0.0001);
                assertTrue(
                        seen.stream().anyMatch(s -> s.state() == DownloadState.DOWNLOADING),
                        "should pass through DOWNLOADING");
            }
        }
    }

    @Test
    void cancelStopsTheDownload(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            String meta = metalink(data, List.of(mirror.url()), true);
            try (FakeMirror metaHost = metalinkHost(meta)) {
                Path dest = dir.resolve("file.zim");
                DownloadHandle handle = new HttpMultiSourceTransport()
                        .start(entry(metaHost.url() + ".meta4", data.length), dest, ProgressListener.NONE);
                handle.cancel();
                DownloadResult result = await(handle);
                assertTrue(
                        result.state() == DownloadState.CANCELLED || result.ok(),
                        "cancel must settle the future, not hang: " + result.state());
            }
        }
    }

    @Test
    void fallsBackToASingleStreamWhenTheMetalinkIsMissing(@TempDir Path dir) throws Exception {
        byte[] data = content();
        try (FakeMirror mirror = new FakeMirror(data, FakeMirror.Behaviour.NORMAL)) {
            Path dest = dir.resolve("file.zim");
            // No .meta4 context registered on this server → 404 → fall back to the .zim URL.
            ZimEntry entry = entry(mirror.url() + ".meta4", data.length);
            DownloadResult result = await(new HttpMultiSourceTransport().start(entry, dest, ProgressListener.NONE));
            assertTrue(result.ok(), result.message());
            assertArrayEquals(data, Files.readAllBytes(dest));
        }
    }

    /** A server that serves only the metalink document at {@code <url>.meta4}. */
    private static FakeMirror metalinkHost(String metalinkXml) throws IOException {
        return FakeMirror.withMetalink(new byte[0], ignored -> metalinkXml);
    }
}
