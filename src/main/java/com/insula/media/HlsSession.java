package com.insula.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One video being watched: a complete playlist over segments encoded on demand.
 *
 * <p>Segments are cached in memory rather than on disk — they are small (200–500 kB measured),
 * they are cheap to regenerate, and a session is transient, so writing them out would mean a cache
 * to evict and stale files to reap for no gain. The map is bounded by <b>bytes</b>, as every other
 * cache here is, because segment size varies with the content.
 *
 * <p>Requests for the same segment are coalesced: the player and the read-ahead can ask at once,
 * and encoding the same six seconds twice would be pure waste.
 */
public final class HlsSession {

    private static final Logger LOG = Logger.getLogger(HlsSession.class.getName());

    /** ~64 MB holds several minutes of segments; well past what a player buffers ahead. */
    static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    private final String sourceUrl;
    private final double durationSeconds;
    private final double segmentSeconds;
    private final SegmentEncoder encoder;

    /** Access-ordered, evicted by total bytes. */
    private final Map<Integer, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);

    private long cachedBytes;

    private final ExecutorService readAhead = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hls-read-ahead");
        t.setDaemon(true);
        return t;
    });

    /** Produces one segment's bytes; the real one shells out to ffmpeg, tests substitute a fake. */
    public interface SegmentEncoder {
        byte[] encode(String sourceUrl, double start, double duration) throws IOException;
    }

    public HlsSession(String sourceUrl, double durationSeconds, double segmentSeconds, SegmentEncoder encoder) {
        this.sourceUrl = sourceUrl;
        this.durationSeconds = durationSeconds;
        this.segmentSeconds = segmentSeconds;
        this.encoder = encoder;
    }

    public double durationSeconds() {
        return durationSeconds;
    }

    public int segmentCount() {
        return HlsPlaylist.segmentCount(durationSeconds, segmentSeconds);
    }

    /** The playlist, complete and ending in {@code EXT-X-ENDLIST} so the timeline is seekable. */
    public String playlist(String segmentUriFormat) {
        return HlsPlaylist.build(durationSeconds, segmentSeconds, segmentUriFormat);
    }

    /**
     * A segment's bytes, encoding it if this is the first ask. Blocks for the encode (~165 ms
     * measured), which is why the serving side runs on a pool rather than a single thread.
     */
    public byte[] segment(int index) throws IOException {
        if (index < 0 || index >= segmentCount()) {
            return null;
        }
        byte[] cached = lookup(index);
        if (cached != null) {
            scheduleReadAhead(index + 1);
            return cached;
        }
        // Coalesce: one encoder per index, so a concurrent ask waits rather than duplicating work.
        synchronized (lockFor(index)) {
            cached = lookup(index);
            if (cached != null) {
                return cached;
            }
            byte[] bytes = encoder.encode(
                    sourceUrl,
                    HlsPlaylist.segmentStart(index, segmentSeconds),
                    HlsPlaylist.segmentDuration(index, durationSeconds, segmentSeconds));
            if (bytes != null && bytes.length > 0) {
                store(index, bytes);
            }
            scheduleReadAhead(index + 1);
            return bytes;
        }
    }

    private final Map<Integer, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockFor(int index) {
        return locks.computeIfAbsent(index, i -> new Object());
    }

    /**
     * Encodes the next segment while the current one plays. Six seconds of playback against a
     * ~165 ms encode means this is never the bottleneck, but it removes the hitch at a boundary
     * when the machine is busy.
     */
    private void scheduleReadAhead(int index) {
        if (index < 0 || index >= segmentCount() || lookup(index) != null) {
            return;
        }
        readAhead.execute(() -> {
            try {
                segment(index);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Read-ahead failed for segment " + index, e);
            }
        });
    }

    private synchronized byte[] lookup(int index) {
        return cache.get(index);
    }

    private synchronized void store(int index, byte[] bytes) {
        byte[] previous = cache.put(index, bytes);
        cachedBytes += bytes.length - (previous == null ? 0 : previous.length);
        var iterator = cache.entrySet().iterator();
        while (cachedBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
            var eldest = iterator.next();
            if (eldest.getKey() == index) {
                continue; // never evict what was just asked for
            }
            cachedBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    synchronized long cachedBytes() {
        return cachedBytes;
    }

    synchronized int cachedSegments() {
        return cache.size();
    }

    public void dispose() {
        readAhead.shutdownNow();
        synchronized (this) {
            cache.clear();
            cachedBytes = 0;
        }
        locks.clear();
    }

    /** The production encoder: ffmpeg to a temp file, read back, deleted. */
    public static SegmentEncoder ffmpegEncoder(String ffmpeg, Path workDir) {
        return (source, start, duration) -> {
            Files.createDirectories(workDir);
            Path out = Files.createTempFile(workDir, "seg", ".ts");
            try {
                Process process = new ProcessBuilder(Transcoder.segmentArgv(ffmpeg, source, start, duration, out))
                        .redirectErrorStream(true)
                        .start();
                String output =
                        new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IOException("ffmpeg exited with " + exit + ": " + output.strip());
                }
                return Files.readAllBytes(out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while encoding a segment", e);
            } finally {
                Files.deleteIfExists(out);
            }
        };
    }
}
