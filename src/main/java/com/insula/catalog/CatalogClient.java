package com.insula.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fetches pages of the Kiwix OPDS catalog off the FX thread.
 *
 * <p>Requests carry a generation stamp so a stale response — the user kept typing — is dropped
 * rather than overwriting newer results.
 */
public final class CatalogClient implements AutoCloseable {

    public static final String DEFAULT_CATALOG = "https://opds.library.kiwix.org/v2/entries";

    private final String baseUrl;
    private final HttpClient http;
    private final AtomicLong generation = new AtomicLong();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "catalog");
        t.setDaemon(true);
        return t;
    });

    public CatalogClient() {
        this(DEFAULT_CATALOG);
    }

    public CatalogClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Outcome of a catalog query; exactly one of {@code feed}/{@code error} is meaningful. */
    public record Outcome(OpdsCatalogParser.Feed feed, String error) {
        public boolean ok() {
            return error == null || error.isEmpty();
        }
    }

    /**
     * Runs a query and delivers the result to {@code onResult} <b>on the calling side's thread of
     * choice</b> — the callback runs on the catalog thread, so a UI caller must marshal.
     * Superseded requests are silently dropped.
     */
    public void search(String query, String language, int count, Consumer<Outcome> onResult) {
        long stamp = generation.incrementAndGet();
        executor.execute(() -> {
            Outcome outcome = fetch(query, language, count);
            if (stamp == generation.get()) {
                onResult.accept(outcome);
            }
        });
    }

    Outcome fetch(String query, String language, int count) {
        try {
            StringBuilder url = new StringBuilder(baseUrl).append("?count=").append(count);
            if (query != null && !query.isBlank()) {
                url.append("&q=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
            }
            if (language != null && !language.isBlank()) {
                url.append("&lang=").append(URLEncoder.encode(language.strip(), StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .header("User-Agent", "insula")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return new Outcome(null, "Catalog returned HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                return new Outcome(OpdsCatalogParser.parse(in), null);
            }
        } catch (IOException e) {
            return new Outcome(null, "Could not reach the catalog: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "Interrupted");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
