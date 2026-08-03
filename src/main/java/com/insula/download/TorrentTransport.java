package com.insula.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.WebSeedEntry;
import com.insula.AppInfo;
import com.insula.catalog.ZimEntry;

/**
 * Downloads an archive over BitTorrent, using Kiwix's published {@code .torrent}.
 *
 * <p><b>This is an option, never the only way.</b> {@link TransportSelector} keeps
 * {@link HttpMultiSourceTransport} as the registered fallback, because BitTorrent is blocked on
 * many school, library and office networks — exactly the places this content is for.
 *
 * <p>The transport merges the {@code .meta4} mirror list into the session as web seeds (BEP 19).
 * Kiwix's torrents advertise only a few, geographically clustered mirrors while the Metalink for
 * the same file lists more; measured on one real file, 5 of 9 mirrors were missing from the
 * torrent. Kiwix swarms are frequently thin — a niche archive may have one seeder or none — so
 * without web seeds a download can sit at 0 B/s indefinitely and the app merely looks broken.
 *
 * <p>Progress is <b>polled</b> from the session rather than pushed per alert: libtorrent's alert
 * loop fires far faster than any UI can consume, and forwarding each one would flood the caller.
 */
public final class TorrentTransport implements DownloadTransport {

    private static final Logger LOG = Logger.getLogger(TorrentTransport.class.getName());

    public static final String ID = "torrent";

    /**
     * Torrents still being shared after their download finished, by file name.
     *
     * <p>Static because seeding outlives the {@link Job} that created it: the download is over and
     * its row is gone, but the session is still giving back, and something has to be able to show
     * that and stop it. Invisible seeding is the version this replaces — a session running with no
     * way to see or end it is indistinguishable from a leak.
     */
    private static final java.util.Map<String, Seed> SEEDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** One archive still being shared: what it is doing, and how to stop. */
    public record Seed(String name, TorrentHandle handle, SessionManager session, Runnable stop) {

        /** Live swarm figures, or an all-zero reading if the handle has gone. */
        public SwarmStats stats() {
            try {
                if (handle == null || !handle.isValid() || !handle.inSession()) {
                    return new SwarmStats(0, 0, 0, 0, 0, true);
                }
                TorrentStatus status = handle.status();
                return new SwarmStats(
                        status.numPeers(),
                        status.numSeeds(),
                        (long) status.uploadRate(),
                        status.totalPayloadUpload(),
                        0,
                        true);
            } catch (Throwable t) {
                return new SwarmStats(0, 0, 0, 0, 0, true);
            }
        }
    }

    /** Everything currently being shared, newest name order not guaranteed. */
    public static java.util.List<Seed> activeSeeds() {
        return java.util.List.copyOf(SEEDS.values());
    }

    /** Stops every share — used when the feature is switched off, and at shutdown. */
    public static void stopAllSeeds() {
        java.util.List.copyOf(SEEDS.values()).forEach(seed -> {
            try {
                seed.stop().run();
            } catch (Throwable ignored) {
                // best effort: a share that will not stop must not block the rest
            }
        });
        SEEDS.clear();
    }

    /** How long to wait for libtorrent to finish adding a torrent before giving up on it. */
    private static final Duration HANDLE_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration HANDLE_POLL = Duration.ofMillis(50);

    /** How often the session is sampled for progress. */
    private static final Duration POLL = Duration.ofMillis(500);

    /**
     * If nothing has arrived by now, give up so the manager can fall back to HTTP. The user must
     * never be left watching 0 B/s because the swarm is empty and the port is blocked.
     */
    static final Duration STALL_TIMEOUT = Duration.ofSeconds(45);

    private final HttpClient http;
    private final boolean seedAfterDownload;

    public TorrentTransport() {
        this(false);
    }

    /**
     * @param seedAfterDownload whether to keep sharing once complete. Defaults to off: a large
     *     share of these users are on metered or expensive connections, and silently uploading
     *     tens of gigabytes is not something to opt someone into.
     */
    public TorrentTransport(boolean seedAfterDownload) {
        this.seedAfterDownload = seedAfterDownload;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "BitTorrent";
    }

    @Override
    public String sourceNoun() {
        return "peers";
    }

    /** Whether the native library is present and loadable; false makes the selector skip us. */
    public static boolean isAvailable() {
        try {
            com.frostwire.jlibtorrent.LibTorrent.version();
            return true;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "jlibtorrent unavailable; BitTorrent disabled", t);
            return false;
        }
    }

    @Override
    public boolean canHandle(ZimEntry entry) {
        // Guard on the base URL, not the derived one: the sidecar URLs are built by suffixing, so
        // a blank entry still yields a non-blank ".torrent" that would fail only once fetched.
        return entry != null && !entry.zimUrl().isBlank() && isAvailable();
    }

    @Override
    public DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener) {
        Job job = new Job(entry, destination, listener == null ? ProgressListener.NONE : listener);
        job.begin();
        return job;
    }

    /** Downloads the {@code .torrent} sidecar. */
    private byte[] fetchTorrentFile(ZimEntry entry) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.torrentUrl()))
                .header("User-Agent", AppInfo.USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("No .torrent for " + entry.fileName() + " (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    /** Reads the Metalink mirror list, so its mirrors can be added as web seeds. */
    private List<String> metalinkMirrors(ZimEntry entry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(entry.metalinkUrl()))
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return List.of();
            }
            try (InputStream in = response.body()) {
                return MetalinkParser.parse(in).mirrors();
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "No metalink mirrors to merge for " + entry.fileName(), e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private final class Job implements DownloadHandle {

        private final ZimEntry entry;
        private final Path destination;
        private final ProgressListener listener;

        private final CompletableFuture<DownloadResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicReference<ProgressSnapshot> latest =
                new AtomicReference<>(ProgressSnapshot.of(DownloadState.QUEUED, 0, 0));

        private volatile SessionManager session;
        private volatile int webSeedCount;
        private volatile Seed seed;
        private volatile Thread worker;

        Job(ZimEntry entry, Path destination, ProgressListener listener) {
            this.entry = entry;
            this.destination = destination;
            this.listener = listener;
        }

        void begin() {
            worker = new Thread(this::run, "zim-torrent");
            worker.setDaemon(true);
            worker.start();
        }

        private void run() {
            SessionManager manager = null;
            Path workDir = null;
            try {
                publish(DownloadState.CONNECTING, 0, 0, 0, 0, "Fetching torrent");
                TorrentInfo info = new TorrentInfo(fetchTorrentFile(entry));

                // Seeding means libtorrent must keep serving the file it wrote. There is no
                // moveStorage in the Java API, so a download that is going to be seeded is written
                // straight into the archives folder — moving it afterwards would leave the session
                // serving a path that no longer exists, which is what "seeding" used to do.
                workDir = seedAfterDownload
                        ? destination.toAbsolutePath().getParent()
                        : destination.toAbsolutePath().getParent().resolve(".torrent-" + entry.name());
                Files.createDirectories(workDir);

                manager = new SessionManager();
                session = manager;
                manager.start();

                manager.download(info, workDir.toFile());
                TorrentHandle handle = awaitHandle(manager, info);

                int added = mergeWebSeeds(handle, info);
                webSeedCount = added;
                publish(
                        DownloadState.DOWNLOADING,
                        0,
                        info.totalSize(),
                        0,
                        0,
                        added > 0 ? "Added " + added + " mirrors as web seeds" : "");

                awaitCompletion(handle, info);
                if (cancelled.get()) {
                    finish(DownloadResult.cancelled(destination, latest.get().bytesCompleted()));
                    return;
                }

                Path produced = workDir.resolve(info.files().fileName(0));
                if (!Files.isRegularFile(produced)) {
                    produced = workDir.resolve(info.name());
                }
                if (!produced.equals(destination)) {
                    // Moving invalidates the session's storage, so anything still seeding stops
                    // here rather than pretending to share a file it can no longer read.
                    Files.move(produced, destination, StandardCopyOption.REPLACE_EXISTING);
                    stopSeeding();
                } else if (seedAfterDownload) {
                    seed = new Seed(entry.fileName(), handle, manager, this::stopSeeding);
                    SEEDS.put(entry.fileName(), seed);
                }
                finish(DownloadResult.success(destination, Files.size(destination)));
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Torrent transport failed for " + entry.fileName(), t);
                finish(DownloadResult.failure(
                        destination, latest.get().bytesCompleted(), String.valueOf(t.getMessage()), t));
            } finally {
                stopSession(manager);
                cleanup(workDir);
            }
        }

        /**
         * Waits for the torrent to be in the session before anything touches its handle.
         *
         * <p>The handle must come from {@code find}, <b>not</b> from the {@code add_torrent}
         * alert, however much the alert looks like the tidier source. Measured, same session and
         * same torrent: a {@code find} handle survives {@code status()} indefinitely, while a
         * handle kept from the alert segfaults within a few calls — libtorrent owns that
         * {@code torrent_handle} and frees it when the alert batch is recycled, so holding on to
         * it is a use-after-free. An attempt to "harden" this by switching to the alert made the
         * crash reproducible in seconds; the experiment is the only reason that is known.
         *
         * <p>{@code download} is asynchronous, so the wait itself is still needed: the handle is
         * only safe to drive once the torrent is actually attached to the session.
         */
        private TorrentHandle awaitHandle(SessionManager manager, TorrentInfo info)
                throws IOException, InterruptedException {
            long deadline = System.nanoTime() + HANDLE_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (cancelled.get()) {
                    throw new InterruptedException("cancelled while adding the torrent");
                }
                TorrentHandle handle = manager.find(info);
                if (handle != null && handle.isValid() && handle.inSession()) {
                    return handle;
                }
                Thread.sleep(HANDLE_POLL.toMillis());
            }
            throw new IOException("libtorrent did not accept the torrent");
        }

        /** Adds the Metalink mirrors the torrent does not already advertise. */
        private int mergeWebSeeds(TorrentHandle handle, TorrentInfo info) {
            List<String> existing = new ArrayList<>();
            for (WebSeedEntry seed : info.webSeeds()) {
                existing.add(seed.url());
            }
            List<String> mirrors = WebSeeds.matchingFile(metalinkMirrors(entry), info.name());
            List<String> missing = WebSeeds.missingFrom(existing, mirrors);
            for (String url : missing) {
                try {
                    handle.addUrlSeed(url);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "Could not add web seed " + url, t);
                }
            }
            LOG.fine(() -> "Torrent for " + entry.fileName() + ": " + existing.size() + " web seeds in the .torrent, "
                    + missing.size() + " added from the .meta4");
            return missing.size();
        }

        private void awaitCompletion(TorrentHandle handle, TorrentInfo info) throws InterruptedException {
            long total = info.totalSize();
            long lastActivityAt = System.nanoTime();
            long lastReceived = 0;

            while (!cancelled.get()) {
                // Re-checked every poll, not just once: the handle stops being valid the moment
                // the torrent leaves the session, and status() on a dead one is the same reach
                // into freed native memory that awaitHandle exists to avoid.
                if (!handle.isValid() || !handle.inSession()) {
                    throw new IllegalStateException("the torrent left the session");
                }
                TorrentStatus status = handle.status();
                long verified = status.totalDone();
                // Liveness must be judged on bytes *received*, not bytes *verified*: totalDone()
                // advances only when a whole piece completes and passes its hash, so on a torrent
                // with few large pieces it reads 0 for the entire transfer. Watching it made a
                // perfectly healthy single-piece download (712 KB in one 4 MiB piece) trip the
                // stall timeout and report failure while data was actively arriving.
                long received = status.totalPayloadDownload();
                int peers = status.numPeers();

                if (status.isFinished() || verified >= total) {
                    publish(DownloadState.DOWNLOADING, total, total, 0, peers, "");
                    return;
                }
                SwarmStats swarm = new SwarmStats(
                        peers,
                        status.numSeeds(),
                        (long) status.uploadRate(),
                        status.totalPayloadUpload(),
                        webSeedCount,
                        false);
                publish(new ProgressSnapshot(
                                paused.get() ? DownloadState.PAUSED : DownloadState.DOWNLOADING,
                                verified,
                                total,
                                paused.get() ? 0 : (long) status.downloadRate(),
                                peers,
                                "")
                        .withSwarm(swarm));

                if (received > lastReceived) {
                    lastReceived = received;
                    lastActivityAt = System.nanoTime();
                } else if (!paused.get() && System.nanoTime() - lastActivityAt > STALL_TIMEOUT.toNanos()) {
                    // Empty swarm and unreachable web seeds: fail so HTTP can take over, rather
                    // than leaving the user watching a bar that will never move.
                    throw new IllegalStateException(
                            "No data after " + STALL_TIMEOUT.toSeconds() + "s (" + peers + " peers)");
                }
                Thread.sleep(POLL.toMillis());
            }
        }

        private void stopSession(SessionManager manager) {
            if (manager == null) {
                return;
            }
            try {
                if (!seedAfterDownload || cancelled.get()) {
                    manager.stop();
                }
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Error stopping torrent session", t);
            }
        }

        private void stopSeeding() {
            Seed current = seed;
            seed = null;
            if (current != null) {
                SEEDS.remove(current.name());
            }
            SessionManager s = session;
            if (s != null) {
                try {
                    s.stop();
                } catch (Throwable ignored) {
                    // stopping a session that is already gone is not worth reporting
                }
            }
        }

        private void cleanup(Path workDir) {
            // The seeding case writes straight into the archives folder, so there is no work dir
            // to delete — and deleting that folder would take the library with it.
            if (workDir == null || seedAfterDownload) {
                return;
            }
            try (var paths = Files.walk(workDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            } catch (IOException ignored) {
                // best effort
            }
        }

        private void publish(DownloadState state, long done, long total, long rate, int peers, String detail) {
            publish(new ProgressSnapshot(state, done, total, rate, peers, detail));
        }

        private void publish(ProgressSnapshot snapshot) {
            latest.set(snapshot);
            listener.onProgress(snapshot);
        }

        private void finish(DownloadResult result) {
            ProgressSnapshot last = latest.get();
            latest.set(new ProgressSnapshot(
                    result.state(), last.bytesCompleted(), last.bytesTotal(), 0, 0, result.message()));
            listener.onProgress(latest.get());
            completion.complete(result);
        }

        @Override
        public void pause() {
            paused.set(true);
            SessionManager s = session;
            if (s != null) {
                try {
                    s.pause();
                } catch (Throwable ignored) {
                    // the poll loop still reports PAUSED
                }
            }
        }

        @Override
        public void resume() {
            paused.set(false);
            SessionManager s = session;
            if (s != null) {
                try {
                    s.resume();
                } catch (Throwable ignored) {
                    // the poll loop still reports DOWNLOADING
                }
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            paused.set(false);
        }

        @Override
        public CompletableFuture<DownloadResult> completion() {
            return completion;
        }

        @Override
        public ProgressSnapshot snapshot() {
            return latest.get();
        }
    }
}
