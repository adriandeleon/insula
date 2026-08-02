package com.insula.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Finds ffmpeg and opens playback sessions, off the FX thread.
 *
 * <p>Mirrors the shape the rest of the app uses for external tools (a single daemon executor,
 * results marshalled with {@link Platform#runLater}, a cached availability probe): ffmpeg is
 * <b>optional</b> and self-gating — absent, the media placeholder simply keeps offering external
 * playback.
 */
public final class TranscodeService {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transcode");
        t.setDaemon(true);
        return t;
    });

    private volatile String ffmpeg = "ffmpeg";
    private volatile String ffprobe = "ffprobe";
    private volatile Boolean available;

    public TranscodeService() {}

    /** Blank falls back to the PATH name, so an unset preference just means "find it". */
    public void configure(String ffmpegPath, String ffprobePath) {
        String newFfmpeg = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.strip();
        String newFfprobe = ffprobePath == null || ffprobePath.isBlank() ? "ffprobe" : ffprobePath.strip();
        if (!newFfmpeg.equals(ffmpeg) || !newFfprobe.equals(ffprobe)) {
            ffmpeg = newFfmpeg;
            ffprobe = newFfprobe;
            available = null; // a changed path must be re-probed, not assumed
        }
    }

    /** Cached: the answer only changes when the configured path does. */
    public void detect(Consumer<Boolean> onDone) {
        Boolean known = available;
        if (known != null) {
            Platform.runLater(() -> onDone.accept(known));
            return;
        }
        worker.execute(() -> {
            boolean found = probeVersion(ffmpeg) && probeVersion(ffprobe);
            available = found;
            Platform.runLater(() -> onDone.accept(found));
        });
    }

    public boolean isAvailable() {
        return Boolean.TRUE.equals(available);
    }

    /**
     * Opens a session for a video: probes its duration, then serves segments on demand. The probe
     * is the only thing standing between a click and playback (measured well under a second), so
     * there is no progress UI — by the time one could be drawn, the video is playing.
     */
    public void openSession(String sourceUrl, Path workDir, Consumer<HlsSession> onReady, Consumer<String> onError) {
        worker.execute(() -> {
            OptionalDouble seconds = duration(sourceUrl);
            if (seconds.isEmpty()) {
                Platform.runLater(() -> onError.accept("Could not read this video's duration"));
                return;
            }
            HlsSession session = new HlsSession(
                    sourceUrl,
                    seconds.getAsDouble(),
                    HlsPlaylist.SEGMENT_SECONDS,
                    HlsSession.ffmpegEncoder(ffmpeg, workDir));
            Platform.runLater(() -> onReady.accept(session));
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private OptionalDouble duration(String sourceUrl) {
        try {
            Process process = new ProcessBuilder(Transcoder.probeArgv(ffprobe, sourceUrl))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return Transcoder.parseDurationSeconds(output);
        } catch (IOException e) {
            return OptionalDouble.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OptionalDouble.empty();
        }
    }

    private static boolean probeVersion(String command) {
        try {
            Process process = new ProcessBuilder(List.of(command, "-version"))
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
