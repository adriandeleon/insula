package com.insula.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.AppInfo;

/**
 * The locally cached copy of the full OPDS catalog — the one artifact the whole Store runs on.
 *
 * <p>Caching the entire feed is what buys instant search, offline browsing, cheap facet counts
 * and trivially testable update detection. It is affordable: the live feed is 3,611 entries in
 * ~4.3 MB of XML, and the server honours {@code If-None-Match}, so an unchanged catalog costs one
 * 304 to confirm.
 *
 * <p>Failure rule: a refresh that fails — network down, malformed payload, disk error — leaves
 * the previous cache untouched. The Store must never be emptier after a refresh than before it.
 */
public final class CatalogCache implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CatalogCache.class.getName());

    public static final String DEFAULT_ENDPOINT = "https://opds.library.kiwix.org/v2/entries";

    /** Older than this on opening the Store → refresh silently in the background. */
    public static final Duration AUTO_REFRESH_AGE = Duration.ofDays(7);
    /** Older than this → the freshness chip turns amber. */
    public static final Duration STALE_AGE = Duration.ofDays(14);

    private final Path directory;
    private final String endpoint;
    private final HttpClient http;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "catalog-cache");
        t.setDaemon(true);
        return t;
    });

    public CatalogCache(Path directory) {
        this(directory, DEFAULT_ENDPOINT);
    }

    public CatalogCache(Path directory, String endpoint) {
        this.directory = directory;
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private Path feedFile() {
        return directory.resolve("entries.xml");
    }

    private Path metaFile() {
        return directory.resolve("catalog.properties");
    }

    /** Parses the cached feed, or empty when none has ever been fetched (or it is unreadable). */
    public Optional<OpdsCatalogParser.Feed> load() {
        if (!Files.isRegularFile(feedFile())) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(feedFile())) {
            return Optional.of(OpdsCatalogParser.parse(in));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cached catalog unreadable; treating as absent", e);
            return Optional.empty();
        }
    }

    /** When the cache was last confirmed current (fetched, or revalidated via 304); 0 = never. */
    public long fetchedAtMillis() {
        return parseLong(meta().getProperty("fetchedAt"), 0);
    }

    public boolean isOlderThan(Duration age, long nowMillis) {
        long fetched = fetchedAtMillis();
        return fetched == 0 || nowMillis - fetched > age.toMillis();
    }

    /** Outcome of a refresh; exactly one of {@code updated}/{@code unchanged}/{@code failed}. */
    public record RefreshOutcome(boolean updated, boolean unchanged, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Refreshes off-thread and reports on the cache's own thread; the caller marshals to FX.
     * Sends {@code If-None-Match} when an ETag is stored, so an unchanged feed costs one 304.
     */
    public void refresh(Consumer<RefreshOutcome> onDone) {
        executor.execute(() -> onDone.accept(refreshNow()));
    }

    RefreshOutcome refreshNow() {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(fullFeedUrl()))
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .timeout(Duration.ofMinutes(3))
                    .GET();
            String etag = meta().getProperty("etag", "");
            if (!etag.isBlank() && Files.isRegularFile(feedFile())) {
                request.header("If-None-Match", etag);
            }
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 304) {
                writeMeta(etag, System.currentTimeMillis(), meta().getProperty("entryCount", "0"));
                return new RefreshOutcome(false, true, null);
            }
            if (response.statusCode() != 200) {
                return new RefreshOutcome(false, false, "Catalog returned HTTP " + response.statusCode());
            }

            // Parse BEFORE swapping in: a malformed payload must never replace a good cache.
            OpdsCatalogParser.Feed feed = OpdsCatalogParser.parse(new java.io.ByteArrayInputStream(response.body()));
            if (feed.entries().isEmpty()) {
                return new RefreshOutcome(false, false, "Catalog feed contained no entries");
            }

            Files.createDirectories(directory);
            Path temp = feedFile().resolveSibling("entries.xml.tmp");
            Files.write(temp, response.body());
            try {
                Files.move(temp, feedFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, feedFile(), StandardCopyOption.REPLACE_EXISTING);
            }
            writeMeta(
                    response.headers().firstValue("ETag").orElse(""),
                    System.currentTimeMillis(),
                    String.valueOf(feed.entries().size()));
            return new RefreshOutcome(true, false, null);
        } catch (IOException e) {
            return new RefreshOutcome(false, false, "Could not reach the catalog: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RefreshOutcome(false, false, "Interrupted");
        } catch (RuntimeException e) {
            return new RefreshOutcome(false, false, "Catalog refresh failed: " + e.getMessage());
        }
    }

    /** {@code count=-1} asks the server for the whole feed rather than a page. */
    private String fullFeedUrl() {
        return endpoint + "?count=-1";
    }

    /** Resolves a (usually relative) illustration href against the catalog host. */
    public String resolveHref(String href) {
        if (href == null || href.isBlank()) {
            return "";
        }
        return URI.create(endpoint).resolve(href).toString();
    }

    /** URL-encodes nothing itself; kept package-visible for the icon fetcher later. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Properties meta() {
        Properties props = new Properties();
        if (Files.isRegularFile(metaFile())) {
            try (InputStream in = Files.newInputStream(metaFile())) {
                props.load(in);
            } catch (IOException ignored) {
                // absent metadata just means "never fetched"
            }
        }
        return props;
    }

    private void writeMeta(String etag, long fetchedAt, String entryCount) throws IOException {
        Properties props = new Properties();
        props.setProperty("etag", etag == null ? "" : etag);
        props.setProperty("fetchedAt", String.valueOf(fetchedAt));
        props.setProperty("entryCount", entryCount);
        Files.createDirectories(directory);
        Path temp = metaFile().resolveSibling("catalog.properties.tmp");
        try (var out = Files.newOutputStream(temp)) {
            props.store(out, "Insula catalog cache");
        }
        try {
            Files.move(temp, metaFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, metaFile(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Entries from the cache, or an empty list — never a network call. */
    public List<ZimEntry> entries() {
        return load().map(OpdsCatalogParser.Feed::entries).orElse(List.of());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
