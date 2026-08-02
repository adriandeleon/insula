package com.insula.media;

import java.util.Locale;

/**
 * Builds a <b>complete</b> HLS playlist for a video that has not been transcoded yet.
 *
 * <p>This is what makes playback start immediately. The duration comes from a probe, the segment
 * boundaries are chosen here, and every segment is listed up front with {@code EXT-X-ENDLIST} —
 * so the player sees the whole timeline, knows the real duration, and can seek anywhere, while
 * the bytes behind each segment are produced only when it asks for them.
 *
 * <p>Writing the playlist ourselves rather than letting ffmpeg grow one is the essential move:
 * JavaFX's player reads a playlist <em>once, at load</em>, so a playlist that grows is a playlist
 * whose later half the player never learns about (measured: it clamped to the 14 s that existed
 * at that instant, on a 25-minute talk).
 */
public final class HlsPlaylist {

    /**
     * Six seconds. Long enough that the per-segment process cost (~165 ms measured) is a small
     * fraction of the content it produces, short enough that a seek lands quickly.
     */
    public static final double SEGMENT_SECONDS = 6.0;

    private HlsPlaylist() {}

    /** How many segments a video of this length needs; a trailing partial second still gets one. */
    public static int segmentCount(double durationSeconds, double segmentSeconds) {
        if (durationSeconds <= 0 || segmentSeconds <= 0) {
            return 0;
        }
        return (int) Math.ceil(durationSeconds / segmentSeconds);
    }

    public static double segmentStart(int index, double segmentSeconds) {
        return index * segmentSeconds;
    }

    /** The last segment is short: the video does not end on a boundary. */
    public static double segmentDuration(int index, double durationSeconds, double segmentSeconds) {
        double start = segmentStart(index, segmentSeconds);
        return Math.max(0, Math.min(segmentSeconds, durationSeconds - start));
    }

    /**
     * @param segmentUri a format string taking the segment index, e.g. {@code "%d.ts"}
     */
    public static String build(double durationSeconds, double segmentSeconds, String segmentUri) {
        int count = segmentCount(durationSeconds, segmentSeconds);
        StringBuilder text = new StringBuilder(128 + count * 24);
        text.append("#EXTM3U\n")
                .append("#EXT-X-VERSION:3\n")
                .append("#EXT-X-PLAYLIST-TYPE:VOD\n")
                .append("#EXT-X-INDEPENDENT-SEGMENTS\n")
                .append("#EXT-X-MEDIA-SEQUENCE:0\n")
                // Must be >= the longest segment or a strict player rejects the playlist.
                .append("#EXT-X-TARGETDURATION:")
                .append((long) Math.ceil(segmentSeconds))
                .append('\n');
        for (int i = 0; i < count; i++) {
            text.append("#EXTINF:")
                    .append(String.format(Locale.ROOT, "%.3f", segmentDuration(i, durationSeconds, segmentSeconds)))
                    .append(",\n")
                    .append(String.format(Locale.ROOT, segmentUri, i))
                    .append('\n');
        }
        // The end marker is what tells the player this is a finite, seekable timeline.
        return text.append("#EXT-X-ENDLIST\n").toString();
    }
}
