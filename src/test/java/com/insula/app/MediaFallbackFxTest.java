package com.insula.app;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.reader.MediaBridge;
import com.insula.reader.MediaFallback;
import com.insula.reader.WebViewRenderer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media fallback against the real engine, which is the only thing that can answer "can you
 * play this?" — the whole design delegates that judgement to {@code canPlayType}, so a test that
 * stubbed it would be testing nothing.
 */
class MediaFallbackFxTest {

    private static WebViewRenderer renderer;
    private static HttpServer server;
    private static volatile String page = "";

    /**
     * The pages are served over real HTTP rather than {@code loadContent}, because the fallback
     * resolves each source against {@code document.baseURI} — and an {@code about:blank} document
     * has no base a relative URL can resolve against. Serving them gives the same footing the app
     * has, where every article comes off the loopback server.
     */
    @BeforeAll
    static void openRenderer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        renderer = FxTestSupport.callOnFx(() -> {
            WebViewRenderer r = new WebViewRenderer();
            Stage stage = new Stage();
            stage.setScene(new Scene((javafx.scene.Parent) r.node(), 640, 480));
            return r;
        });
    }

    @AfterAll
    static void closeRenderer() throws Exception {
        FxTestSupport.runOnFx(() -> renderer.dispose());
        server.stop(0);
    }

    private static void load(String html) throws Exception {
        page = html;
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/article";
        CountDownLatch loaded = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            renderer.engine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            renderer.load(url);
        });
        assertTrue(loaded.await(20, TimeUnit.SECONDS), "content must load");
    }

    private static int install() {
        Object replaced = FxTestSupport.callOnFx(
                () -> renderer.runScript(MediaFallback.installScript(MediaFallback.defaultLabel(), "Play")));
        return replaced instanceof Number n ? n.intValue() : -1;
    }

    @Test
    void aWebmVideoIsReplacedAndItsPosterKept() throws Exception {
        // Exactly the TED markup: a single VP9 WebM source with a poster frame.
        load("""
                <html><body><video id="v" controls poster="thumb.png">
                  <source src="videos/129163/video.webm" type="video/webm">
                </video></body></html>
                """);
        assertEquals(1, install(), "the engine reports it cannot play WebM, so the element is replaced");

        Object gone = FxTestSupport.callOnFx(() -> renderer.runScript("document.querySelectorAll('video').length"));
        assertEquals(0, ((Number) gone).intValue(), "the dead player is removed, not merely hidden");
        Object placeholder = FxTestSupport.callOnFx(
                () -> renderer.runScript("document.querySelectorAll('.insula-media-fallback').length"));
        assertEquals(1, ((Number) placeholder).intValue());
        Object poster = FxTestSupport.callOnFx(() ->
                renderer.runScript("document.querySelector('.insula-media-fallback img')" + " ? 'kept' : 'lost'"));
        assertEquals("kept", poster, "losing the visual entirely would read as a broken page");
    }

    @Test
    void runningTwiceReplacesNothingTheSecondTime() throws Exception {
        load("<html><body><video><source src=\"a.webm\" type=\"video/webm\"></video></body></html>");
        assertEquals(1, install());
        assertEquals(0, install(), "the guard makes a re-run on a second load event a no-op");
    }

    @Test
    void mediaTheEngineCanPlayIsLeftAlone() throws Exception {
        // canPlayType('video/mp4') answers "maybe" on this engine, so the player must survive.
        load("<html><body><video id=\"v\" controls><source src=\"a.mp4\" type=\"video/mp4\"></video></body></html>");
        assertEquals(0, install(), "an archive shipping MP4 keeps its real player");
        Object still = FxTestSupport.callOnFx(() -> renderer.runScript("document.querySelectorAll('video').length"));
        assertEquals(1, ((Number) still).intValue());
    }

    @Test
    void clickingPlaySendsTheAbsoluteUrlThroughTheBridge() throws Exception {
        load("<html><body><video poster=\"p.png\"><source src=\"videos/1/video.webm\" type=\"video/webm\">"
                + "</video></body></html>");
        List<String> opened = new ArrayList<>();
        FxTestSupport.runOnFx(() -> renderer.installBridge(MediaFallback.BRIDGE, new MediaBridge(opened::add)));
        assertEquals(1, install());

        FxTestSupport.runOnFx(
                () -> renderer.runScript("document.querySelector('.insula-media-fallback button').click()"));
        assertEquals(1, opened.size(), "the button reaches Java");
        assertTrue(opened.getFirst().endsWith("videos/1/video.webm"), opened.getFirst());
        assertTrue(
                opened.getFirst().startsWith("http://127.0.0.1:"),
                "an external player needs the absolute served URL, not the relative src: " + opened.getFirst());
    }

    @Test
    void progressAndPlayerSwapRunAgainstARealDocument() throws Exception {
        load("<html><body><video poster=\"p.png\"><source src=\"v.webm\" type=\"video/webm\"></video></body></html>");
        Object replaced = FxTestSupport.callOnFx(() -> renderer.runScript(
                MediaFallback.installScript(MediaFallback.transcodeLabel(), "Open externally", "Play here")));
        assertEquals(1, ((Number) replaced).intValue());

        // Progress replaces the buttons in place rather than rebuilding the placeholder.
        Object progressed = FxTestSupport.callOnFx(
                () -> renderer.runScript(MediaFallback.progressScript("insula-media-0", 42, "Preparing…")));
        assertEquals(Boolean.TRUE, progressed);
        Object caption = FxTestSupport.callOnFx(() ->
                renderer.runScript("document.querySelector('#insula-media-0 .insula-media-bar span').textContent"));
        assertEquals("Preparing…", caption);
    }

    @Test
    void theInAppButtonIsPresentOnlyWhenATranscoderExists() throws Exception {
        String page = "<html><body><video><source src=\"v.webm\" type=\"video/webm\"></video></body></html>";
        load(page);
        FxTestSupport.callOnFx(() -> renderer.runScript(MediaFallback.installScript("l", "Open externally", null)));
        Object withoutFfmpeg = FxTestSupport.callOnFx(
                () -> renderer.runScript("document.querySelectorAll('.insula-media-bar button').length"));
        assertEquals(1, ((Number) withoutFfmpeg).intValue(), "only the external route is offered");

        load(page);
        FxTestSupport.callOnFx(
                () -> renderer.runScript(MediaFallback.installScript("l", "Open externally", "Play here")));
        Object withFfmpeg = FxTestSupport.callOnFx(
                () -> renderer.runScript("document.querySelectorAll('.insula-media-bar button').length"));
        assertEquals(2, ((Number) withFfmpeg).intValue(), "in-app play joins the external route");
    }

    @Test
    void progressOnAMissingPlaceholderIsHarmless() throws Exception {
        // A navigation can retire the box between the encode finishing and the callback landing.
        load("<html><body><p>no media</p></body></html>");
        assertEquals(
                Boolean.FALSE,
                FxTestSupport.callOnFx(() -> renderer.runScript(MediaFallback.progressScript("gone", 10, "x"))));
    }
}
