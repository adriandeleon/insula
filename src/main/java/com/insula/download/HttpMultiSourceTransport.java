package com.insula.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.AppInfo;
import com.insula.catalog.ZimEntry;

/**
 * Downloads over HTTP from several mirrors at once.
 *
 * <p>Reads the entry's {@code .meta4} for the mirror list and piece hashes, splits the file into
 * piece-aligned chunks, and fetches chunks concurrently with {@code Range} requests, writing each
 * into a preallocated sparse file. A chunk whose Metalink piece hashes do not match is discarded
 * and retried on a different mirror, so corruption is caught as it happens rather than hours later
 * at the final whole-file checksum.
 *
 * <p>Progress is pushed to the listener as it happens; coalescing for the UI is the caller's job
 * (see the 4 Hz rule in the downloads panel). Nothing here touches JavaFX.
 *
 * <p>Falls back to a single-stream download against the MirrorBrain URL when the sidecar is
 * missing, when the server does not honour ranges, or when the file has no piece hashes and only
 * one mirror is known.
 */
public final class HttpMultiSourceTransport implements DownloadTransport {

    private static final Logger LOG = Logger.getLogger(HttpMultiSourceTransport.class.getName());

    public static final String ID = "http-multisource";

    /** Concurrent chunk requests. Kept modest: these are donated mirrors, not a CDN. */
    static final int DEFAULT_PARALLELISM = 4;
    /** Attempts per chunk before the download fails; each retry moves to the next mirror. */
    static final int MAX_CHUNK_ATTEMPTS = 4;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final String USER_AGENT = AppInfo.USER_AGENT;
    /** Suffix of the sidecar holding the resume bitmap. */
    static final String PART_SUFFIX = ".part";

    private final HttpClient http;
    private final int parallelism;

    public HttpMultiSourceTransport() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                DEFAULT_PARALLELISM);
    }

    HttpMultiSourceTransport(HttpClient http, int parallelism) {
        this.http = http;
        this.parallelism = parallelism;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "HTTP";
    }

    @Override
    public String sourceNoun() {
        return "mirrors";
    }

    @Override
    public boolean canHandle(ZimEntry entry) {
        // HTTP is the guaranteed path: any entry with a URL can be fetched, worst case single-stream.
        return entry != null && !entry.zimUrl().isBlank();
    }

    @Override
    public DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener) {
        Job job = new Job(entry, destination, listener == null ? ProgressListener.NONE : listener);
        job.begin();
        return job;
    }

    /** Fetches and parses the entry's Metalink sidecar; empty when unavailable or unparseable. */
    Metalink fetchMetalink(ZimEntry entry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(entry.metalinkUrl()))
                    .header("User-Agent", USER_AGENT)
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
            LOG.log(Level.FINE, "No usable metalink for " + entry.fileName(), e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** One in-flight download. */
    private final class Job implements DownloadHandle {

        private final ZimEntry entry;
        private final Path destination;
        private final Path partFile;
        private final ProgressListener listener;

        private final CompletableFuture<DownloadResult> completion = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean paused = new AtomicBoolean();
        private final AtomicLong completedBytes = new AtomicLong();
        private final AtomicInteger activeSources = new AtomicInteger();
        private final AtomicReference<ProgressSnapshot> latest =
                new AtomicReference<>(ProgressSnapshot.of(DownloadState.QUEUED, 0, 0));

        private final ExecutorService pool = Executors.newFixedThreadPool(parallelism + 1, r -> {
            Thread t = new Thread(r, "zim-download");
            t.setDaemon(true);
            return t;
        });

        private volatile long lastRateSampleTime;
        private volatile long lastRateSampleBytes;
        private volatile long bytesPerSecond;

        /**
         * Authoritative byte count. Starts at the catalog's figure, which is only an approximation
         * — the OPDS acquisition {@code length} is rounded <em>up</em> to a whole KiB (measured:
         * 712704 published for a 712215-byte archive), so trusting it leaves progress stuck just
         * short of 100%. Replaced by the Metalink {@code <size>} as soon as the sidecar is read.
         */
        private volatile long totalBytes;

        Job(ZimEntry entry, Path destination, ProgressListener listener) {
            this.entry = entry;
            this.destination = destination;
            this.partFile = destination.resolveSibling(destination.getFileName() + PART_SUFFIX);
            this.listener = listener;
            this.totalBytes = entry.sizeBytes();
        }

        void begin() {
            pool.execute(() -> {
                try {
                    run();
                } catch (Throwable t) {
                    // Never let a failure strand completion() — the UI waits on it.
                    LOG.log(Level.WARNING, "Download failed for " + entry.fileName(), t);
                    finish(DownloadResult.failure(destination, completedBytes.get(), String.valueOf(t), t));
                }
            });
        }

        private void run() throws Exception {
            publish(DownloadState.CONNECTING, "Fetching mirror list");
            Metalink metalink = fetchMetalink(entry);

            long size = metalink != null && metalink.size() > 0 ? metalink.size() : entry.sizeBytes();
            totalBytes = size;
            List<String> mirrors = new ArrayList<>();
            if (metalink != null) {
                mirrors.addAll(metalink.mirrors());
            }
            if (mirrors.isEmpty()) {
                mirrors.add(entry.zimUrl()); // MirrorBrain redirects to a nearby mirror
            }

            if (size <= 0) {
                singleStream(mirrors.getFirst());
                return;
            }

            ChunkPlan plan = ChunkPlan.forFile(
                    size,
                    metalink != null && metalink.hasPieceHashes() ? metalink.pieceLength() : 0,
                    metalink != null ? metalink.pieceHashes().size() : 0);
            resume(plan);
            completedBytes.set(plan.completedBytes());

            Files.createDirectories(destination.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(
                    destination, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
                if (channel.size() < size) {
                    channel.truncate(0); // a stale shorter file would leave holes we never write
                    preallocate(channel, size);
                }
                publish(DownloadState.DOWNLOADING, mirrors.size() + " mirrors");
                fetchChunks(plan, mirrors, metalink, channel);
            }

            if (cancelled.get()) {
                finish(DownloadResult.cancelled(destination, completedBytes.get()));
                return;
            }
            Files.deleteIfExists(partFile);
            finish(DownloadResult.success(destination, completedBytes.get()));
        }

        private void preallocate(FileChannel channel, long size) throws IOException {
            // Write one byte at the end: the file system creates a sparse file, so we can write
            // chunks out of order without the intermediate ranges reading as garbage.
            channel.write(ByteBuffer.allocate(1), size - 1);
        }

        private void resume(ChunkPlan plan) {
            try {
                if (Files.isRegularFile(partFile) && Files.isRegularFile(destination)) {
                    String bitmap = Files.readString(partFile, StandardCharsets.US_ASCII)
                            .strip();
                    if (plan.applyBitmap(bitmap)) {
                        LOG.fine(() -> "Resuming " + entry.fileName() + " at " + plan.completedBytes() + " bytes");
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not read resume state; starting over", e);
            }
        }

        private void saveResumeState(ChunkPlan plan) {
            try {
                Files.writeString(partFile, plan.toBitmap(), StandardCharsets.US_ASCII);
            } catch (IOException e) {
                LOG.log(Level.FINE, "Could not persist resume state", e);
            }
        }

        private void fetchChunks(ChunkPlan plan, List<String> mirrors, Metalink metalink, FileChannel channel)
                throws Exception {
            List<ChunkPlan.Chunk> pending = plan.pending();
            AtomicInteger cursor = new AtomicInteger();
            AtomicReference<Exception> firstError = new AtomicReference<>();

            List<CompletableFuture<Void>> workers = new ArrayList<>();
            for (int w = 0; w < Math.min(parallelism, Math.max(1, pending.size())); w++) {
                final int workerIndex = w;
                workers.add(CompletableFuture.runAsync(
                        () -> {
                            int i;
                            while ((i = cursor.getAndIncrement()) < pending.size()) {
                                if (cancelled.get() || firstError.get() != null) {
                                    return;
                                }
                                awaitUnpaused();
                                ChunkPlan.Chunk chunk = pending.get(i);
                                try {
                                    fetchChunk(chunk, mirrors, metalink, channel, workerIndex);
                                    synchronized (plan) {
                                        plan.markDone(chunk.index());
                                        saveResumeState(plan);
                                    }
                                } catch (Exception e) {
                                    firstError.compareAndSet(null, e);
                                    return;
                                }
                            }
                        },
                        pool));
            }
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).join();

            if (firstError.get() != null && !cancelled.get()) {
                throw firstError.get();
            }
        }

        private void fetchChunk(
                ChunkPlan.Chunk chunk, List<String> mirrors, Metalink metalink, FileChannel channel, int workerIndex)
                throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < MAX_CHUNK_ATTEMPTS; attempt++) {
                if (cancelled.get()) {
                    return;
                }
                // Spread workers across mirrors, and move to a different one on each retry.
                String mirror = mirrors.get((workerIndex + attempt + chunk.index()) % mirrors.size());
                activeSources.incrementAndGet();
                try {
                    byte[] data = requestRange(mirror, chunk);
                    if (data.length != chunk.length()) {
                        throw new IOException("Short chunk from " + mirror + ": " + data.length + "/" + chunk.length());
                    }
                    verifyPieces(chunk, data, metalink);
                    synchronized (channel) {
                        channel.write(ByteBuffer.wrap(data), chunk.start());
                    }
                    addCompleted(data.length);
                    return;
                } catch (Exception e) {
                    last = e;
                    LOG.log(Level.FINE, "Chunk " + chunk.index() + " failed from " + mirror, e);
                } finally {
                    activeSources.decrementAndGet();
                }
            }
            throw new IOException("Chunk " + chunk.index() + " failed after " + MAX_CHUNK_ATTEMPTS + " attempts", last);
        }

        private byte[] requestRange(String mirror, ChunkPlan.Chunk chunk) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(mirror))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", chunk.rangeHeader())
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 206) {
                return response.body();
            }
            if (response.statusCode() == 200) {
                // Server ignored the Range header and sent the whole file — usable only for a
                // single-chunk plan, otherwise we would silently write the wrong bytes.
                if (chunk.start() == 0 && response.body().length == chunk.length()) {
                    return response.body();
                }
                throw new IOException("Mirror ignored Range: " + mirror);
            }
            throw new IOException("HTTP " + response.statusCode() + " from " + mirror);
        }

        /** Validates the chunk against the Metalink piece hashes, when the sidecar has them. */
        private void verifyPieces(ChunkPlan.Chunk chunk, byte[] data, Metalink metalink) throws Exception {
            if (metalink == null || !metalink.hasPieceHashes() || chunk.firstPiece() < 0) {
                return;
            }
            MessageDigest sha1 = sha1();
            long pieceLength = metalink.pieceLength();
            List<String> hashes = metalink.pieceHashes();
            for (int offset = 0, piece = chunk.firstPiece();
                    offset < data.length && piece < hashes.size();
                    piece++, offset += pieceLength) {
                int length = (int) Math.min(pieceLength, data.length - offset);
                sha1.reset();
                sha1.update(data, offset, length);
                String actual = HexFormat.of().formatHex(sha1.digest());
                if (!actual.equals(hashes.get(piece))) {
                    throw new IOException("Piece " + piece + " hash mismatch");
                }
            }
        }

        private static MessageDigest sha1() throws NoSuchAlgorithmException {
            return MessageDigest.getInstance("SHA-1");
        }

        /** Whole-file single stream: no ranges, no mirror list, no resume. The last resort. */
        private void singleStream(String url) throws Exception {
            publish(DownloadState.DOWNLOADING, "Single stream");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            Files.createDirectories(destination.toAbsolutePath().getParent());
            try (InputStream in = response.body();
                    var out = Files.newOutputStream(destination)) {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancelled.get()) {
                        finish(DownloadResult.cancelled(destination, completedBytes.get()));
                        return;
                    }
                    awaitUnpaused();
                    out.write(buffer, 0, n);
                    addCompleted(n);
                }
            }
            finish(DownloadResult.success(destination, completedBytes.get()));
        }

        private void awaitUnpaused() {
            while (paused.get() && !cancelled.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void addCompleted(long bytes) {
            long total = completedBytes.addAndGet(bytes);
            long now = System.nanoTime();
            long elapsed = now - lastRateSampleTime;
            if (elapsed > 500_000_000L) { // resample the rate about twice a second
                if (lastRateSampleTime != 0) {
                    bytesPerSecond = (total - lastRateSampleBytes) * 1_000_000_000L / elapsed;
                }
                lastRateSampleTime = now;
                lastRateSampleBytes = total;
            }
            publish(paused.get() ? DownloadState.PAUSED : DownloadState.DOWNLOADING, "");
        }

        private void publish(DownloadState state, String detail) {
            ProgressSnapshot snapshot = new ProgressSnapshot(
                    state, completedBytes.get(), totalBytes, bytesPerSecond, activeSources.get(), detail);
            latest.set(snapshot);
            listener.onProgress(snapshot);
        }

        private void finish(DownloadResult result) {
            latest.set(new ProgressSnapshot(result.state(), completedBytes.get(), totalBytes, 0, 0, result.message()));
            listener.onProgress(latest.get());
            pool.shutdownNow();
            completion.complete(result);
        }

        @Override
        public void pause() {
            paused.set(true);
            publish(DownloadState.PAUSED, "");
        }

        @Override
        public void resume() {
            paused.set(false);
            publish(DownloadState.DOWNLOADING, "");
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
