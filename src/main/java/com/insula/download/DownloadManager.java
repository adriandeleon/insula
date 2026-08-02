package com.insula.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.AppInfo;
import com.insula.catalog.ZimEntry;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;

/**
 * Runs the whole acquisition pipeline for an entry: pick a transport, download, verify the
 * whole-file SHA-256, then either admit the archive to the library or quarantine it.
 *
 * <p>Everything happens off the FX thread. Progress is exposed as a snapshot per download for a UI
 * that samples on a timer — this class never calls into JavaFX and never pushes an event per byte.
 */
public final class DownloadManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(DownloadManager.class.getName());

    /** One tracked download. */
    public static final class Job {
        private final ZimEntry entry;
        private final Path destination;
        private volatile ProgressSnapshot snapshot;
        private volatile DownloadHandle handle;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile String transportName = "";
        private volatile String sourceNoun = "sources";

        Job(ZimEntry entry, Path destination) {
            this.entry = entry;
            this.destination = destination;
            this.snapshot = ProgressSnapshot.of(DownloadState.QUEUED, 0, entry.sizeBytes());
        }

        public ZimEntry entry() {
            return entry;
        }

        public Path destination() {
            return destination;
        }

        public ProgressSnapshot snapshot() {
            return snapshot;
        }

        /**
         * The protocol actually moving these bytes ("HTTP", "BitTorrent"), or {@code ""} before
         * one is chosen. It changes mid-download when a preferred transport stalls and the
         * pipeline falls back, which is precisely when a user wants to be told.
         */
        public String transportName() {
            return transportName;
        }

        /** What {@link ProgressSnapshot#connectedSources()} counts for the active transport. */
        public String sourceNoun() {
            return sourceNoun;
        }

        public void cancel() {
            cancelled.set(true);
            DownloadHandle h = handle;
            if (h != null) {
                h.cancel();
            }
        }

        public void pause() {
            DownloadHandle h = handle;
            if (h != null) {
                h.pause();
            }
        }

        public void resume() {
            DownloadHandle h = handle;
            if (h != null) {
                h.resume();
            }
        }
    }

    private final TransportSelector selector;
    private final Library library;
    private final Path downloadDir;
    private final HttpClient http;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /** Fired after a verified download is admitted to the library, on the pipeline thread. */
    private volatile Consumer<Job> onAdmitted = job -> {};

    private final ExecutorService pipeline = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "download-pipeline");
        t.setDaemon(true);
        return t;
    });

    public DownloadManager(TransportSelector selector, Library library, Path downloadDir) {
        this.selector = selector;
        this.library = library;
        this.downloadDir = downloadDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** UI hook for post-admit chores (e.g. offering to delete a superseded older build). */
    public void setOnAdmitted(Consumer<Job> onAdmitted) {
        this.onAdmitted = onAdmitted;
    }

    /** Currently tracked downloads, newest state included. */
    public List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    public Job job(ZimEntry entry) {
        return jobs.get(entry.id());
    }

    /** Queues an entry. Returns the existing job if it is already downloading. */
    public Job enqueue(ZimEntry entry) {
        return jobs.computeIfAbsent(entry.id(), id -> {
            Job job = new Job(entry, downloadDir.resolve(entry.fileName()));
            pipeline.execute(() -> runPipeline(job));
            return job;
        });
    }

    private void runPipeline(Job job) {
        try {
            Files.createDirectories(downloadDir);
            try {
                RecoverySidecar.write(job.destination(), job.entry());
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not write recovery sidecar", e); // repair degrades, download proceeds
            }
            DownloadTransport transport = selector.select(job.entry());
            DownloadResult result = download(job, transport);

            // A preferred transport that could not deliver falls back to the guaranteed HTTP path.
            DownloadTransport fallback = selector.fallbackFor(job.entry());
            if (!result.ok() && result.state() != DownloadState.CANCELLED && transport != fallback) {
                LOG.info(() -> "Falling back to " + fallback.id() + " for "
                        + job.entry().fileName());
                job.snapshot = new ProgressSnapshot(
                        DownloadState.CONNECTING, 0, job.entry().sizeBytes(), 0, 0, "Switched to direct download");
                result = download(job, fallback);
            }

            if (!result.ok()) {
                job.snapshot = new ProgressSnapshot(
                        result.state(),
                        job.snapshot.bytesCompleted(),
                        job.entry().sizeBytes(),
                        0,
                        0,
                        result.message());
                return;
            }
            verify(job);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Download pipeline failed for " + job.entry().fileName(), t);
            job.snapshot = new ProgressSnapshot(
                    DownloadState.FAILED,
                    job.snapshot.bytesCompleted(),
                    job.entry().sizeBytes(),
                    0,
                    0,
                    String.valueOf(t));
        }
    }

    private DownloadResult download(Job job, DownloadTransport transport) throws Exception {
        job.transportName = transport.displayName();
        job.sourceNoun = transport.sourceNoun();
        // The transport's COMPLETED means "the bytes landed", not "this archive is usable" — the
        // checksum still has to pass. Publishing it verbatim would make the job look terminal, so
        // a UI polling for a terminal state shows "Ready" on an unverified (possibly corrupt)
        // file, and a shutdown at that moment would cancel verification entirely. Translate it to
        // VERIFYING and let the pipeline decide the real terminal state.
        DownloadHandle handle = transport.start(job.entry(), job.destination(), snapshot -> {
            job.snapshot = snapshot.state() == DownloadState.COMPLETED
                    ? new ProgressSnapshot(
                            DownloadState.VERIFYING,
                            snapshot.bytesCompleted(),
                            snapshot.bytesTotal(),
                            0,
                            0,
                            "Checking integrity")
                    : snapshot;
        });
        job.handle = handle;
        return handle.completion().get();
    }

    private void verify(Job job) {
        String published = fetchSha256(job.entry());
        if (published.isBlank()) {
            // No published digest: admit it, but never claim it was verified.
            LOG.warning(() -> "No .sha256 for " + job.entry().fileName() + "; admitting unverified");
            admit(job, "", false);
            long size = authoritativeSize(job);
            job.snapshot = new ProgressSnapshot(
                    DownloadState.COMPLETED, size, size, 0, 0, "Downloaded (no checksum published)");
            return;
        }
        try {
            long total = authoritativeSize(job);
            Sha256Verifier.Verification verification = Sha256Verifier.verify(
                    job.destination(),
                    published,
                    read -> job.snapshot =
                            new ProgressSnapshot(DownloadState.VERIFYING, read, total, 0, 0, "Verifying checksum"),
                    () -> false);

            if (verification.ok()) {
                admit(job, verification.actual(), true);
                job.snapshot = new ProgressSnapshot(DownloadState.COMPLETED, total, total, 0, 0, "Verified");
            } else {
                Path quarantined = Quarantine.quarantine(job.destination());
                job.snapshot = new ProgressSnapshot(
                        DownloadState.QUARANTINED,
                        job.snapshot.bytesCompleted(),
                        total,
                        0,
                        0,
                        verification.describe() + " — kept at " + quarantined.getFileName());
            }
        } catch (IOException e) {
            job.snapshot = new ProgressSnapshot(
                    DownloadState.FAILED,
                    job.snapshot.bytesCompleted(),
                    job.entry().sizeBytes(),
                    0,
                    0,
                    "Verification failed: " + e.getMessage());
        }
    }

    /**
     * The real byte count: what is on disk, else what the transport learned from the Metalink.
     * Never {@code entry.sizeBytes()} — the catalog rounds that up to a whole KiB, which would
     * leave the progress bar short of 100% on every download.
     */
    private static long authoritativeSize(Job job) {
        try {
            long onDisk = Files.size(job.destination());
            if (onDisk > 0) {
                return onDisk;
            }
        } catch (IOException ignored) {
            // fall through to what the transport reported
        }
        long reported = job.snapshot().bytesTotal();
        return reported > 0 ? reported : Math.max(1, job.entry().sizeBytes());
    }

    private void admit(Job job, String sha256, boolean verified) {
        long size;
        try {
            size = Files.size(job.destination());
        } catch (IOException e) {
            size = job.entry().sizeBytes();
        }
        synchronized (library) {
            library.put(new LibraryEntry(
                    job.destination(), job.entry().title(), size, sha256, verified, System.currentTimeMillis()));
            library.save();
        }
        RecoverySidecar.delete(job.destination());
        if (verified) {
            onAdmitted.accept(job);
        }
    }

    /**
     * Picks up files whose verification a previous run never finished: a bare {@code .zim} with a
     * recovery sidecar and no in-flight {@code .part}. The sidecar's Metalink supplies the
     * published SHA-256; unreachable network keeps the file pending for the next launch.
     * Runs on the pipeline executor; callbacks arrive on that thread.
     */
    public void resumePendingVerification(Consumer<String> onStatus) {
        pipeline.execute(() -> {
            List<Path> pending = pendingVerifications();
            for (Path zim : pending) {
                RecoverySidecar.Info info = RecoverySidecar.read(zim).orElse(null);
                if (info == null) {
                    continue;
                }
                onStatus.accept("Finishing verification of " + zim.getFileName());
                Metalink metalink = fetchMetalinkByUrl(info.metalinkUrl());
                if (metalink == null) {
                    onStatus.accept("Verification of " + zim.getFileName() + " postponed (offline?)");
                    continue;
                }
                verifyStandalone(zim, info.title(), metalink, onStatus);
            }
        });
    }

    /** Bare {@code .zim} files with a sidecar and no {@code .part} — quit-during-verify leftovers. */
    List<Path> pendingVerifications() {
        try (var stream = Files.list(downloadDir)) {
            return stream.filter(f -> f.getFileName().toString().endsWith(".zim"))
                    .filter(f -> Files.isRegularFile(RecoverySidecar.sidecarFor(f)))
                    .filter(f -> !Files.exists(f.resolveSibling(f.getFileName() + ".part")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void verifyStandalone(Path zim, String title, Metalink metalink, Consumer<String> onStatus) {
        String published = Sha256Verifier.normalize(metalink.sha256Hash().orElse(""));
        try {
            if (published.isBlank()) {
                admitFile(zim, title, "", false);
                onStatus.accept(zim.getFileName() + " admitted (no checksum published)");
                return;
            }
            Sha256Verifier.Verification verification = Sha256Verifier.verify(zim, published, read -> {}, () -> false);
            if (verification.ok()) {
                admitFile(zim, title, verification.actual(), true);
                onStatus.accept(zim.getFileName() + " verified");
            } else {
                Path quarantined = Quarantine.quarantine(zim);
                onStatus.accept(zim.getFileName() + " failed verification — kept at " + quarantined.getFileName());
            }
        } catch (IOException e) {
            onStatus.accept("Verification of " + zim.getFileName() + " failed: " + e.getMessage());
        }
    }

    /**
     * Repairs a quarantined file piece by piece (see {@link PieceRepair}), then admits it.
     * Needs the recovery sidecar written when the file was downloaded; without it there is no
     * Metalink to repair against. Runs on the pipeline executor.
     */
    public void repairQuarantined(Path quarantined, Consumer<String> onStatus, Consumer<Boolean> onDone) {
        pipeline.execute(() -> {
            boolean ok = false;
            try {
                Path zim = RecoverySidecar.zimFor(quarantined);
                RecoverySidecar.Info info = RecoverySidecar.read(zim).orElse(null);
                if (info == null) {
                    onStatus.accept(
                            "No recovery info for " + quarantined.getFileName() + " — delete it and download again");
                    return;
                }
                if (Files.exists(zim)) {
                    onStatus.accept(zim.getFileName() + " already exists — delete the quarantined copy");
                    return;
                }
                Metalink metalink = fetchMetalinkByUrl(info.metalinkUrl());
                if (metalink == null) {
                    onStatus.accept("Could not fetch the Metalink for " + quarantined.getFileName());
                    return;
                }
                PieceRepair.Result result = PieceRepair.repair(
                        quarantined,
                        metalink,
                        http,
                        phase -> onStatus.accept(quarantined.getFileName() + ": " + phase));
                if (!result.ok()) {
                    onStatus.accept("Repair failed: " + result.message());
                    return;
                }
                Files.move(quarantined, zim);
                admitFile(zim, info.title(), result.sha256(), !result.sha256().isBlank());
                onStatus.accept(zim.getFileName() + " repaired — " + result.message());
                ok = true;
            } catch (IOException e) {
                onStatus.accept("Repair failed: " + e.getMessage());
            } finally {
                onDone.accept(ok);
            }
        });
    }

    /** Fetches and parses a Metalink by URL; null when unreachable or unparseable. */
    Metalink fetchMetalinkByUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            try (InputStream in = response.body()) {
                return MetalinkParser.parse(in);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Metalink fetch failed: " + url, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void admitFile(Path file, String title, String sha256, boolean verified) throws IOException {
        long size = Files.size(file);
        synchronized (library) {
            library.put(new LibraryEntry(file, title, size, sha256, verified, System.currentTimeMillis()));
            library.save();
        }
        RecoverySidecar.delete(file);
    }

    String fetchSha256(ZimEntry entry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(entry.sha256Url()))
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return "";
            }
            try (InputStream in = response.body()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not fetch checksum for " + entry.fileName(), e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    @Override
    public void close() {
        jobs.values().forEach(Job::cancel);
        pipeline.shutdownNow();
    }
}
