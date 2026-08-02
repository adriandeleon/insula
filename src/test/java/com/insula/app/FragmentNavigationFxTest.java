package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fragment navigation is not a new article.
 *
 * <p>The engine reports {@code #anchor} exactly like a real navigation, so without the guard a
 * table-of-contents click ran the whole article-changed path: Reader View exited, and the reading
 * position stored under the anchored spelling got restored over the heading the reader had just
 * asked for.
 */
class FragmentNavigationFxTest {

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        stage.setScene(new Scene(controller.root(), 900, 600));
        return controller;
    }

    /**
     * Waits for the document to finish loading, not merely for the location to be set — the
     * location changes when navigation <em>starts</em>, and scripting a not-yet-parsed document
     * silently does nothing.
     */
    private static void awaitArticle(ReaderController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            boolean ready = FxTestSupport.callOnFx(() -> {
                String location = controller.rendererForTest().engine().getLocation();
                return location != null
                        && location.contains("/zim/")
                        && controller.rendererForTest().engine().getLoadWorker().getState()
                                == javafx.concurrent.Worker.State.SUCCEEDED;
            });
            if (ready) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the archive never finished loading an article");
    }

    @Test
    void aHeadingClickKeepsReaderViewAndTheArticleIdentity(@TempDir Path dir) throws Exception {
        Path zim = dir.resolve("nons-wikibooks.zim");
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);

        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.openZim(zim));
            awaitArticle(controller);

            String before = FxTestSupport.callOnFx(
                    () -> controller.rendererForTest().engine().getLocation());
            String path = FxTestSupport.callOnFx(controller::currentArticlePathForTest);
            assertTrue(path != null && !path.isBlank(), "an article path was recorded");

            // Exactly what a table-of-contents link does.
            FxTestSupport.runOnFx(() -> controller.rendererForTest().runScript("window.location.hash = '#a-section';"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                String now = FxTestSupport.callOnFx(
                        () -> controller.rendererForTest().engine().getLocation());
                if (now != null && now.contains("#a-section")) {
                    break;
                }
                Thread.sleep(30);
            }
            String after = FxTestSupport.callOnFx(
                    () -> controller.rendererForTest().engine().getLocation());
            assertTrue(after.contains("#a-section"), "the fragment navigation happened: " + after);
            assertTrue(after.startsWith(before), "same document, only a fragment added");

            assertEquals(
                    path,
                    FxTestSupport.callOnFx(controller::currentArticlePathForTest),
                    "the anchor must not register as a different article");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
