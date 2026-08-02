package com.insula.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The instant-start path: a complete playlist over segments produced on demand. */
class HlsTest {

    @Test
    void thePlaylistIsCompleteAndMarkedFinished() {
        // Completeness is the whole point: JavaFX reads a playlist once, so anything missing at
        // that moment is missing forever, and without ENDLIST the timeline is not seekable.
        String playlist = HlsPlaylist.build(25.0, 6.0, "%d.ts");
        assertTrue(playlist.startsWith("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"));
        assertTrue(playlist.trim().endsWith("#EXT-X-ENDLIST"));
        assertEquals(5, playlist.lines().filter(l -> l.endsWith(".ts")).count(), "4 full segments plus a short one");
        assertTrue(playlist.contains("0.ts") && playlist.contains("4.ts"));
    }

    @Test
    void theTargetDurationCoversTheLongestSegment() {
        // A player is entitled to reject a playlist whose TARGETDURATION is under any EXTINF.
        String playlist = HlsPlaylist.build(30.0, 6.0, "%d.ts");
        long target = playlist.lines()
                .filter(l -> l.startsWith("#EXT-X-TARGETDURATION:"))
                .mapToLong(l -> Long.parseLong(l.substring(l.indexOf(':') + 1)))
                .findFirst()
                .orElseThrow();
        double longest = playlist.lines()
                .filter(l -> l.startsWith("#EXTINF:"))
                .mapToDouble(l -> Double.parseDouble(l.substring(8, l.indexOf(','))))
                .max()
                .orElseThrow();
        assertTrue(target >= longest, "target " + target + " must cover " + longest);
    }

    @Test
    void theLastSegmentIsShortAndTheRestAreFull() {
        assertEquals(5, HlsPlaylist.segmentCount(25.0, 6.0));
        assertEquals(0.0, HlsPlaylist.segmentStart(0, 6.0));
        assertEquals(24.0, HlsPlaylist.segmentStart(4, 6.0));
        assertEquals(6.0, HlsPlaylist.segmentDuration(0, 25.0, 6.0));
        assertEquals(1.0, HlsPlaylist.segmentDuration(4, 25.0, 6.0), 1e-9, "the video does not end on a boundary");
        // A duration that lands exactly on a boundary must not produce an empty trailing segment.
        assertEquals(4, HlsPlaylist.segmentCount(24.0, 6.0));
    }

    @Test
    void anUnknownDurationYieldsNoSegmentsRatherThanNonsense() {
        assertEquals(0, HlsPlaylist.segmentCount(0, 6.0));
        assertEquals(0, HlsPlaylist.segmentCount(-1, 6.0));
        assertEquals(0, HlsPlaylist.segmentCount(25, 0));
    }

    @Test
    void segmentArgvSeeksBeforeInputAndStampsAbsoluteTime() {
        List<String> argv = Transcoder.segmentArgv("ffmpeg", "http://h/v.webm", 1200, 6, Path.of("/tmp/s.ts"));
        int ss = argv.indexOf("-ss");
        int input = argv.indexOf("-i");
        assertTrue(ss >= 0 && ss < input, "-ss must precede -i, or ffmpeg decodes from the start");
        assertEquals("1200.000", argv.get(ss + 1));
        // Absolute timestamps are what let segments stitch into one continuous timeline.
        assertTrue(argv.contains("-output_ts_offset"));
        assertEquals("1200.000", argv.get(argv.indexOf("-output_ts_offset") + 1));
        assertTrue(argv.contains("mpegts"));
        assertTrue(argv.contains("libx264") && argv.contains("aac"));
    }

    /** Records what was asked for, so the session's behaviour is observable without ffmpeg. */
    private static final class FakeEncoder implements HlsSession.SegmentEncoder {
        final List<Double> starts = new CopyOnWriteArrayList<>();

        @Override
        public byte[] encode(String source, double start, double duration) {
            starts.add(start);
            return ("segment@" + start).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void aSegmentIsEncodedOnceAndThenServedFromMemory() throws Exception {
        FakeEncoder encoder = new FakeEncoder();
        HlsSession session = new HlsSession("http://h/v.webm", 60, 6, encoder);
        try {
            assertEquals("segment@12.0", new String(session.segment(2), StandardCharsets.UTF_8));
            session.segment(2);
            session.segment(2);
            assertEquals(
                    1,
                    Collections.frequency(encoder.starts, 12.0),
                    "re-encoding six seconds already held in memory would be pure waste");
        } finally {
            session.dispose();
        }
    }

    @Test
    void seekingStraightToTheEndEncodesOnlyThatSegment() throws Exception {
        // This is the property the whole design exists for: reaching minute 20 must not require
        // producing minutes 0-19 first.
        FakeEncoder encoder = new FakeEncoder();
        HlsSession session = new HlsSession("http://h/v.webm", 1500, 6, encoder);
        try {
            assertNotNull(session.segment(200));
            assertTrue(encoder.starts.contains(1200.0));
            assertTrue(
                    encoder.starts.stream().noneMatch(s -> s < 1200.0),
                    "nothing before the seek point should have been encoded: " + encoder.starts);
        } finally {
            session.dispose();
        }
    }

    @Test
    void outOfRangeSegmentsAreReportedNotFabricated() throws Exception {
        HlsSession session = new HlsSession("http://h/v.webm", 60, 6, new FakeEncoder());
        try {
            assertEquals(10, session.segmentCount());
            assertNull(session.segment(10));
            assertNull(session.segment(-1));
        } finally {
            session.dispose();
        }
    }

    @Test
    void theSegmentCacheIsBoundedByBytes() throws Exception {
        HlsSession.SegmentEncoder big = (source, start, duration) -> new byte[8 * 1024 * 1024];
        HlsSession session = new HlsSession("http://h/v.webm", 6000, 6, big);
        try {
            for (int i = 0; i < 20; i++) {
                session.segment(i);
            }
            assertTrue(
                    session.cachedBytes() <= HlsSession.MAX_CACHE_BYTES,
                    "held " + session.cachedBytes() + " over a " + HlsSession.MAX_CACHE_BYTES + " budget");
            assertTrue(session.cachedSegments() > 0, "eviction must not empty the cache entirely");
        } finally {
            session.dispose();
        }
    }

    @Test
    void playerTimesReadAsPeopleWriteThem() {
        assertEquals("0:00", com.insula.app.VideoPlayerPaneAccess.format(0));
        assertEquals("0:07", com.insula.app.VideoPlayerPaneAccess.format(7));
        assertEquals("2:05", com.insula.app.VideoPlayerPaneAccess.format(125));
        assertEquals("1:00:00", com.insula.app.VideoPlayerPaneAccess.format(3600));
        assertEquals("0:00", com.insula.app.VideoPlayerPaneAccess.format(Double.NaN), "an unknown time is not a crash");
    }

    @Test
    void anEncoderFailurePropagatesRatherThanServingEmptyBytes() {
        HlsSession session = new HlsSession("http://h/v.webm", 60, 6, (s, a, b) -> {
            throw new IOException("ffmpeg said no");
        });
        try {
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> session.segment(0))
                    .getMessage()
                    .contains("ffmpeg"));
        } finally {
            session.dispose();
        }
    }
}
