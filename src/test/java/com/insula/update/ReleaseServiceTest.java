package com.insula.update;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.insula.AppInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fetch, against a real loopback server.
 *
 * <p>Per the project's rule that anything networked is tested against a server that can also
 * misbehave: the interesting cases here are a rate-limited GitHub, a body that never ends, and a
 * response that is not JSON — all of which reach a user as "check for updates" doing nothing, and
 * none of which a mocked client would exercise.
 */
class ReleaseServiceTest {

    /** Runs a server that answers one canned response, and hands its URL to the body. */
    private static void withServer(int status, String body, Consumer<String> test) throws IOException {
        withServer(
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                },
                test);
    }

    private static void withServer(com.sun.net.httpserver.HttpHandler handler, Consumer<String> test)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", handler);
        server.start();
        try {
            test.accept("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
        } finally {
            server.stop(0);
        }
    }

    private static ReleaseService.Outcome fetch(String url, String current) {
        ReleaseService service = new ReleaseService(url);
        try {
            return service.fetch(current);
        } finally {
            service.shutdown();
        }
    }

    private static final String RELEASE = """
            {"tag_name": "v0.9.0", "html_url": "https://example.invalid/r", "name": "Insula 0.9.0"}
            """;

    @Test
    void reportsANewerRelease() throws IOException {
        withServer(200, RELEASE, url -> {
            ReleaseService.Outcome outcome = fetch(url, "0.1.0");
            assertTrue(outcome.available());
            assertNotNull(outcome.latest());
            assertEquals("0.9.0", outcome.latest().version());
            assertNull(outcome.error());
        });
    }

    @Test
    void staysQuietWhenTheReleaseIsNotNewer() throws IOException {
        // Not an error, and nothing to show — the overwhelmingly common case.
        withServer(200, RELEASE, url -> {
            ReleaseService.Outcome outcome = fetch(url, "1.0.0");
            assertFalse(outcome.available());
            assertNull(outcome.error());
        });
    }

    @Test
    void sendsTheHeadersGitHubRequires() throws IOException {
        // Without a User-Agent GitHub answers 403, which would look like "no updates, ever".
        AtomicReference<String> agent = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        withServer(
                exchange -> {
                    agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
                    accept.set(exchange.getRequestHeaders().getFirst("Accept"));
                    byte[] bytes = RELEASE.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                },
                url -> fetch(url, "0.1.0"));
        assertEquals(AppInfo.USER_AGENT, agent.get());
        assertEquals("application/vnd.github+json", accept.get());
    }

    @Test
    void reportsAnHttpErrorRatherThanPretendingThereIsNoUpdate() throws IOException {
        // 403 is what a rate-limited or UA-less request gets, and it is worth telling someone who
        // asked directly — silence here is indistinguishable from being up to date.
        withServer(403, "{\"message\": \"rate limit exceeded\"}", url -> {
            ReleaseService.Outcome outcome = fetch(url, "0.1.0");
            assertFalse(outcome.available());
            assertNotNull(outcome.error());
            assertTrue(outcome.error().contains("403"));
        });
    }

    @Test
    void aResponseThatIsNotJsonIsNoUpdate() throws IOException {
        // A captive portal answering a login page with 200 is the realistic version of this.
        withServer(200, "<html><body>Sign in to continue</body></html>", url -> {
            ReleaseService.Outcome outcome = fetch(url, "0.1.0");
            assertFalse(outcome.available());
            assertNull(outcome.error(), "unusable, but nothing a user could act on");
        });
    }

    @Test
    void refusesABodyThatNeverEnds() throws IOException {
        // The reason the cap is on the read rather than on a Content-Length the server chose:
        // this response declares nothing and just keeps going.
        withServer(
                exchange -> {
                    exchange.sendResponseHeaders(200, 0); // chunked, length unknown
                    byte[] chunk = new byte[64 * 1024];
                    java.util.Arrays.fill(chunk, (byte) ' ');
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int i = 0; i < 64; i++) { // 4 MB, well past the 1 MB cap
                            out.write(chunk);
                        }
                    } catch (IOException expected) {
                        // the client hangs up once it has had enough; that is the point
                    }
                },
                url -> {
                    ReleaseService.Outcome outcome = fetch(url, "0.1.0");
                    assertFalse(outcome.available());
                    assertNotNull(outcome.error(), "refused, and said so");
                });
    }

    @Test
    void anUnreachableHostIsAnErrorNotACrash() {
        // Port 1 on loopback: refused immediately, no waiting for a timeout.
        ReleaseService.Outcome outcome = fetch("http://127.0.0.1:1/latest", "0.1.0");
        assertFalse(outcome.available());
        assertNotNull(outcome.error());
    }
}
