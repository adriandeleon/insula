package com.insula.media;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure half of in-app video: argv, progress parsing, and cache naming. */
class TranscoderTest {

    @Test
    void theEncodeIsShapedForPlaybackNotForSize() {
        List<String> argv = Transcoder.transcodeArgv("ffmpeg", "http://host/v.webm", Path.of("/tmp/out.mp4"));
        assertEquals("ffmpeg", argv.getFirst());
        assertTrue(argv.contains("libx264"), "H.264 is the codec JavaFX can actually decode");
        assertTrue(argv.contains("aac"));
        // faststart puts the index first so playback can begin without reading the tail.
        assertTrue(argv.contains("+faststart"));
        // Machine-readable progress instead of scraping the human status line.
        assertTrue(argv.contains("-progress") && argv.contains("pipe:1"));
        assertTrue(argv.contains("-nostdin"), "a stalled prompt would hang the worker forever");
        assertEquals("/tmp/out.mp4", argv.getLast());
        // The container must be explicit: the encode writes to a ".part" file first, and ffmpeg
        // infers the format from the extension otherwise ("Unable to choose an output format").
        assertTrue(argv.contains("-f") && argv.contains("mp4"));
        assertTrue(argv.contains("http://host/v.webm"), "ffmpeg reads the source over HTTP, never a copy");
    }

    @Test
    void durationIsReadFromFfprobesBareOutput() {
        assertEquals(1533.616, Transcoder.parseDurationSeconds("1533.616000\n").getAsDouble(), 0.001);
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds("N/A"));
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds(""));
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds(null));
        // A live stream reports 0, which is not a usable total.
        assertEquals(OptionalDouble.empty(), Transcoder.parseDurationSeconds("0.000000"));
    }

    @Test
    void progressLinesYieldSecondsEncoded() {
        assertEquals(
                12.345678,
                Transcoder.parseProgressSeconds("out_time_us=12345678").getAsDouble(),
                1e-6);
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("frame=42"));
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("out_time_us=N/A"));
        // ffmpeg emits a negative value before the first frame lands.
        assertEquals(OptionalDouble.empty(), Transcoder.parseProgressSeconds("out_time_us=-1"));
        assertTrue(Transcoder.isProgressEnd("progress=end"));
        assertFalse(Transcoder.isProgressEnd("progress=continue"));
    }

    @Test
    void percentIsClampedAndSafeWithoutADuration() {
        assertEquals(50, Transcoder.percent(50, 100));
        assertEquals(100, Transcoder.percent(150, 100), "encoding past the probed duration still reads as done");
        assertEquals(0, Transcoder.percent(-5, 100));
        assertEquals(0, Transcoder.percent(10, 0), "an unknown duration must not divide by zero");
    }

    @Test
    void cacheNamesSeparateIdenticalPathsInDifferentArchives() {
        // "videos/1/video.webm" is a path many archives contain; keying on it alone would serve
        // one archive's talk in place of another's.
        String a = Transcoder.cacheName("/lib/ted.zim", "videos/1/video.webm", 100);
        String b = Transcoder.cacheName("/lib/other.zim", "videos/1/video.webm", 100);
        assertNotEquals(a, b);
        // A re-downloaded archive of a different size must not hit a stale encode.
        assertNotEquals(a, Transcoder.cacheName("/lib/ted.zim", "videos/1/video.webm", 200));
        assertEquals(a, Transcoder.cacheName("/lib/ted.zim", "videos/1/video.webm", 100), "stable");
        assertTrue(a.endsWith(".mp4"));
        assertTrue(a.matches("[0-9a-f]{32}\\.mp4"), a);
    }

    @Test
    void cacheReportsOnlyFinishedFiles(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir.resolve("cache"));
        cache.prepare();
        assertNull(cache.lookup("a.mp4"), "nothing cached yet");

        Files.write(cache.fileFor("a.mp4"), new byte[0]);
        assertNull(cache.lookup("a.mp4"), "a zero-length file is a failed encode, not a hit");

        Files.write(cache.fileFor("a.mp4"), new byte[] {1, 2, 3});
        assertEquals(cache.fileFor("a.mp4"), cache.lookup("a.mp4"));

        // A hit records recency itself, because atime is unreliable on relatime/noatime mounts.
        Files.setLastModifiedTime(cache.fileFor("a.mp4"), java.nio.file.attribute.FileTime.fromMillis(1000));
        cache.lookup("a.mp4");
        assertTrue(
                Files.getLastModifiedTime(cache.fileFor("a.mp4")).toMillis() > 1000,
                "a cache hit must mark the file as recently used");
    }

    @Test
    void theCacheIsBoundedByBytesBecauseOneVideoIsTensOfMegabytes(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir.resolve("cache"), 4096);
        cache.prepare();
        for (int i = 0; i < 8; i++) {
            Files.write(cache.fileFor("v" + i + ".mp4"), new byte[1024]);
            // Distinct timestamps so "oldest" is well defined rather than filesystem-order luck.
            Files.setLastModifiedTime(
                    cache.fileFor("v" + i + ".mp4"), java.nio.file.attribute.FileTime.fromMillis(1000L + i * 1000));
        }
        cache.evictToBudget();

        long total;
        try (var stream = Files.list(cache.directory())) {
            total = stream.mapToLong(f -> {
                        try {
                            return Files.size(f);
                        } catch (java.io.IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
        assertTrue(total <= 4096, "cache held " + total + " bytes over a 4096 budget");
        assertTrue(Files.exists(cache.fileFor("v7.mp4")), "the newest encode must survive");
        assertFalse(Files.exists(cache.fileFor("v0.mp4")), "the oldest goes first");
    }
}
