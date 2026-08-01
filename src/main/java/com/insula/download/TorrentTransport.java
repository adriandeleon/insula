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

                workDir = destination.toAbsolutePath().getParent().resolve(".torrent-" + entry.name());
                Files.createDirectories(workDir);

                manager = new SessionManager();
                session = manager;
                manager.start();

                manager.download(info, workDir.toFile());
                TorrentHandle handle = manager.find(info);
                if (handle == null) {
                    throw new IOException("libtorrent did not accept the torrent");
                }

                int added = mergeWebSeeds(handle, info);
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

                // libtorrent wrote <workDir>/<name>; move it to where the manager expects it.
                Path produced = workDir.resolve(info.files().fileName(0));
                if (!Files.isRegularFile(produced)) {
                    produced = workDir.resolve(info.name());
                }
                Files.move(produced, destination, StandardCopyOption.REPLACE_EXISTING);
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
                if (paused.get()) {
                    publish(DownloadState.PAUSED, verified, total, 0, peers, "");
                } else {
                    publish(DownloadState.DOWNLOADING, verified, total, (long) status.downloadRate(), peers, "");
                }

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

        private void cleanup(Path workDir) {
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
            ProgressSnapshot snapshot = new ProgressSnapshot(state, done, total, rate, peers, detail);
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
