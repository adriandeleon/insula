package com.insula.app;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.reader.ReaderView;
import com.insula.reader.ReaderViewSession;
import com.insula.reader.WebViewRenderer;
import com.insula.server.ZimHttpServer;
import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reader View against the real pipeline: a fixture archive served over the loopback server into a
 * real WebView, with Mozilla's actual Readability doing the extraction. The pure tests prove the
 * strings; only this proves the strings run.
 */
class ReaderViewFxTest {

    private static ZimArchive archive;
    private static ZimHttpServer server;
    private static WebViewRenderer renderer;
    private static String token;

    @BeforeAll
    static void openFixture() throws Exception {
        archive = ZimArchive.open(Path.of("src/test/resources/zim/nons-wikibooks.zim"));
        server = new ZimHttpServer();
        token = server.register(archive);
        renderer = FxTestSupport.callOnFx(() -> {
            WebViewRenderer r = new WebViewRenderer();
            Stage stage = new Stage();
            stage.setScene(new Scene((javafx.scene.Parent) r.node(), 800, 600));
            return r;
        });
    }

    @AfterAll
    static void closeFixture() throws Exception {
        FxTestSupport.runOnFx(() -> renderer.dispose());
        server.close();
        archive.close();
    }

    /** Loads the fixture's main page and blocks until the document is fully parsed. */
    private static void loadMainPage() throws Exception {
        Dirent main = archive.mainPage().orElseThrow();
        String url = server.urlFor(token, main.fullPath());
        CountDownLatch loaded = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            renderer.engine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    loaded.countDown();
                }
            });
            renderer.load(url);
        });
        assertTrue(loaded.await(30, TimeUnit.SECONDS), "the fixture page must load");
    }

    @Test
    void readabilityExtractsAndTheShellSwapsInThenPrefsRestyleLive() throws Exception {
        loadMainPage();
        ReaderViewSession session = new ReaderViewSession(renderer::runScript);

        // Enter: real extraction, real DOM swap.
        boolean entered = FxTestSupport.callOnFx(
                () -> session.enter("Wikibooks", ReaderView.Prefs.normalized("serif", 20, 680, 1.6, "light")));
        assertTrue(entered, "the wikibooks main page must be extractable");
        assertTrue(session.words() > 0, "a real word count came back over the bridge");

        String title = String.valueOf(
                FxTestSupport.callOnFx(() -> renderer.runScript("document.querySelector('.rv-title').textContent")));
        assertFalse(title.isBlank(), "the shell shows the article title");
        Object meta =
                FxTestSupport.callOnFx(() -> renderer.runScript("document.querySelector('.rv-meta').textContent"));
        assertTrue(String.valueOf(meta).contains("minute"), "the reading time is in the header: " + meta);
        Object archiveCss = FxTestSupport.callOnFx(
                () -> renderer.runScript("document.querySelectorAll('link[rel~=\"stylesheet\"]').length"));
        assertEquals(0, ((Number) archiveCss).intValue(), "the archive's stylesheets are gone");

        // Live restyle: dark theme lands in the computed background, not just in a string.
        String before = String.valueOf(
                FxTestSupport.callOnFx(() -> renderer.runScript("getComputedStyle(document.body).backgroundColor")));
        FxTestSupport.runOnFx(() -> session.applyPrefs(ReaderView.Prefs.normalized("sans", 24, 680, 1.6, "dark")));
        String after = String.valueOf(
                FxTestSupport.callOnFx(() -> renderer.runScript("getComputedStyle(document.body).backgroundColor")));
        assertNotEquals(before, after, "the theme change must actually repaint");
        assertEquals("rgb(28, 27, 34)", after, "Firefox's dark reader background, #1c1b22");
    }

    @Test
    void probeAnswersOnARealDocument() throws Exception {
        loadMainPage();
        ReaderViewSession session = new ReaderViewSession(renderer::runScript);
        // The fixture main page is a listing-like page; the probe may answer either way, but it
        // must answer — a script error would surface as null → false and, worse, nondeterminism.
        Boolean answer = FxTestSupport.callOnFx(session::probe);
        assertTrue(answer != null, "the probe always returns a boolean");
    }
}
