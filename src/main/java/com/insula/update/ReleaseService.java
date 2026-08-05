package com.insula.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.insula.AppInfo;

/**
 * Asks GitHub whether there is a newer Insula, off the FX thread.
 *
 * <p>The impure half; {@link ReleaseCheck} makes every decision. One daemon thread, because this
 * runs at most once a day and a thread pool for that is furniture. Answers arrive on the FX thread.
 *
 * <p>It has no opinion about whether it <em>should</em> run — the caller owns the offline switch,
 * the once-a-day throttle and the setting. This just asks and reports.
 */
public final class ReleaseService {

    /** The whole payload for one release. Generous for a long Markdown body, far short of a flood. */
    private static final int MAX_BYTES = 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** What a check came back with: at most one of {@code latest} and {@code error} is set. */
    public record Outcome(ReleaseInfo latest, String error) {

        /** Whether a release strictly newer than this build was found. */
        public boolean available() {
            return latest != null;
        }
    }

    private final HttpClient http;
    private final String endpoint;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });

    public ReleaseService() {
        this(AppInfo.LATEST_RELEASE_API);
    }

    /** The endpoint is injectable so tests can point it at a loopback server rather than GitHub. */
    public ReleaseService(String endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Checks in the background and reports on the FX thread.
     *
     * @param currentVersion what is running; a newer release is one strictly above it
     */
    public void check(String currentVersion, Consumer<Outcome> onDone) {
        worker.execute(() -> {
            Outcome outcome = fetch(currentVersion);
            Platform.runLater(() -> onDone.accept(outcome));
        });
    }

    /** The blocking check, for tests and for {@link #check}. Never throws. */
    Outcome fetch(String currentVersion) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    // GitHub answers 403 without a User-Agent, and asks for an explicit API version.
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return new Outcome(null, "GitHub answered " + response.statusCode());
            }
            byte[] body = readCapped(response.body());
            ReleaseInfo latest = ReleaseCheck.parseLatest(body);
            if (latest == null) {
                return new Outcome(null, null); // no usable release; not an error worth reporting
            }
            return new Outcome(ReleaseCheck.isNewer(currentVersion, latest.version()) ? latest : null, null);
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            return new Outcome(null, message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, null); // shutting down; nothing to report
        }
    }

    /**
     * Reads at most {@link #MAX_BYTES}, streaming.
     *
     * <p>Not {@code BodyHandlers.ofByteArray}, which sizes itself from a {@code Content-Length} the
     * server chose: a response claiming — or simply streaming — gigabytes would be buffered whole
     * before anything got the chance to reject it. The cap has to be on the read.
     */
    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] body = in.readNBytes(MAX_BYTES);
            if (body.length == MAX_BYTES && in.read() != -1) {
                throw new IOException("release payload larger than " + MAX_BYTES + " bytes");
            }
            return body;
        }
    }

    private static String message(Exception e) {
        String detail = e.getMessage();
        return detail == null || detail.isBlank() ? e.getClass().getSimpleName() : detail;
    }

    public void shutdown() {
        worker.shutdownNow();
    }
}
