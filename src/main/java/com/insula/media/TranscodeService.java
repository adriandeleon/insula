package com.insula.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

/**
 * Runs ffmpeg off the FX thread and reports progress back onto it.
 *
 * <p>Mirrors the shape the rest of the app uses for external tools (a single daemon executor,
 * results marshalled with {@link Platform#runLater}, a cached availability probe): ffmpeg is
 * <b>optional</b> and self-gating — absent, the media placeholder simply keeps offering external
 * playback.
 *
 * <p>Encoding writes to a {@code .part} file and moves it into place only on success, so an
 * interrupted run can never leave a truncated video in the cache to be served as if complete.
 */
public final class TranscodeService {

    private static final Logger LOG = Logger.getLogger(TranscodeService.class.getName());

    /** Progress is coalesced to this cadence, matching the download UI rather than every line. */
    private static final long PROGRESS_INTERVAL_MS = 250;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transcode");
        t.setDaemon(true);
        return t;
    });

    private final MediaCache cache;
    private volatile String ffmpeg = "ffmpeg";
    private volatile String ffprobe = "ffprobe";
    private volatile Boolean available;
    private volatile Process current;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public TranscodeService(MediaCache cache) {
        this.cache = cache;
    }

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

    public MediaCache cache() {
        return cache;
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

    /** How a transcode ended. {@code file} is null unless it succeeded. */
    public record Result(boolean ok, Path file, String message) {}

    /**
     * Transcodes {@code sourceUrl} (read directly over HTTP — no copy of the original is made)
     * into the cache under {@code cacheName}, or returns the cached file immediately.
     */
    public void transcode(String sourceUrl, String cacheName, Consumer<Integer> onProgress, Consumer<Result> onDone) {
        cancelled.set(false);
        worker.execute(() -> {
            try {
                cache.prepare();
                Path finished = cache.lookup(cacheName);
                if (finished != null) {
                    Platform.runLater(() -> onDone.accept(new Result(true, finished, "Ready")));
                    return;
                }
                Path target = cache.fileFor(cacheName);
                Path part = target.resolveSibling(target.getFileName() + ".part");
                double total = duration(sourceUrl).orElse(0);
                Result result = run(sourceUrl, part, total, onProgress);
                if (result.ok()) {
                    Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    cache.evictToBudget();
                    Platform.runLater(() -> onDone.accept(new Result(true, target, "Ready")));
                } else {
                    Files.deleteIfExists(part);
                    Platform.runLater(() -> onDone.accept(result));
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Transcode failed for " + sourceUrl, e);
                Platform.runLater(() -> onDone.accept(new Result(false, null, String.valueOf(e.getMessage()))));
            }
        });
    }

    /** Stops the encode in flight, if any. The partial file is cleaned up by the worker. */
    public void cancel() {
        cancelled.set(true);
        Process process = current;
        if (process != null) {
            process.destroy();
        }
    }

    public void shutdown() {
        cancel();
        worker.shutdownNow();
    }

    private Result run(String sourceUrl, Path part, double total, Consumer<Integer> onProgress) {
        try {
            ProcessBuilder builder = new ProcessBuilder(Transcoder.transcodeArgv(ffmpeg, sourceUrl, part));
            builder.redirectErrorStream(false);
            Process process = builder.start();
            current = process;
            long lastReport = 0;
            int lastPercent = -1;
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    OptionalDouble seconds = Transcoder.parseProgressSeconds(line);
                    if (seconds.isEmpty()) {
                        continue;
                    }
                    int percent = Transcoder.percent(seconds.getAsDouble(), total);
                    long now = System.currentTimeMillis();
                    if (percent != lastPercent && now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastPercent = percent;
                        lastReport = now;
                        int report = percent;
                        Platform.runLater(() -> onProgress.accept(report));
                    }
                }
            }
            int exit = process.waitFor();
            current = null;
            if (cancelled.get()) {
                return new Result(false, null, "Cancelled");
            }
            if (exit != 0) {
                return new Result(false, null, "ffmpeg exited with " + exit + ": " + stderr(process));
            }
            return new Result(true, part, "Ready");
        } catch (IOException e) {
            current = null;
            return new Result(false, null, "Could not run ffmpeg: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current = null;
            return new Result(false, null, "Interrupted");
        }
    }

    private static String stderr(Process process) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && text.length() < 400) {
                text.append(line).append(' ');
            }
            return text.toString().strip();
        } catch (IOException e) {
            return "";
        }
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
