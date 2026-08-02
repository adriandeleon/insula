package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;

import com.insula.catalog.CatalogCache;
import com.insula.download.DownloadState;
import com.insula.download.ProgressSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Catalog card from "Download" to "In library", driven with a fake state supplier so no network
 * or disk is involved.
 *
 * <p>This is the whole point of the 4 Hz sampler, and it was broken: the sampler stops itself the
 * moment nothing is in flight, which happens within a tick of the Catalog opening, and starting a
 * download did nothing to wake it. The card sat on "Download" through the entire transfer.
 */
class CatalogCardLifecycleFxTest {

    /** What the fake supplier reports; the test moves it through the states a real one would. */
    private final AtomicReference<CatalogPane.CardState> state =
            new AtomicReference<>(CatalogPane.CardState.of(CatalogPane.Installed.NO));

    private static void seedCatalog(Path configDir) throws Exception {
        Path catalogDir = configDir.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.copy(Path.of("src/test/resources/opds/starters-sample.xml"), catalogDir.resolve("entries.xml"));
        Files.writeString(
                catalogDir.resolve("catalog.properties"),
                "fetchedAt=" + System.currentTimeMillis() + "\netag=\"seeded\"\nentryCount=3\n");
    }

    private CatalogPane pane(Path dir) {
        CatalogCache cache = new CatalogCache(dir.resolve("catalog"));
        cache.load();
        CatalogPane pane = new CatalogPane(
                cache,
                new IconCache(dir.resolve("icons")),
                (entry, title) -> state.set(new CatalogPane.CardState(
                        CatalogPane.Installed.NO, snapshot(DownloadState.DOWNLOADING, 0.42), () -> {})),
                entry -> state.get(),
                entry -> null,
                p -> {},
                msg -> {},
                () -> 500L * 1024 * 1024 * 1024);
        // A pane must be in a scene for its Timeline to tick.
        new Scene(new StackPane(pane.node()), 1000, 700);
        return pane;
    }

    private static ProgressSnapshot snapshot(DownloadState downloadState, double fraction) {
        return ProgressSnapshot.of(downloadState, (long) (fraction * 1000), 1000L);
    }

    /** Every label across the cards, so an assertion can read what a card actually says. */
    private static String textOf(CatalogPane pane) {
        return FxTestSupport.callOnFx(pane::cardTextForTest);
    }

    private static void awaitText(CatalogPane pane, String fragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (textOf(pane).contains(fragment)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("card never showed \"" + fragment + "\"; it says:\n" + textOf(pane));
    }

    private static void awaitCards(CatalogPane pane) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            int count = FxTestSupport.callOnFx(() -> {
                pane.setLanguagesForTest(java.util.Set.of());
                return pane.renderedCardsForTest();
            });
            if (count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("catalog cards never rendered");
    }

    /** Presses the first card's action button, the way a click would. */
    private static void pressDownload(CatalogPane pane) {
        FxTestSupport.runOnFx(() -> pane.cardNodesForTest().stream()
                .flatMap(CatalogCardLifecycleFxTest::descendants)
                .filter(n -> n instanceof Button b
                        && b.getText() != null
                        && b.getText().startsWith("Download"))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Download button on any card"))
                .fire());
    }

    private static java.util.stream.Stream<javafx.scene.Node> descendants(javafx.scene.Node node) {
        return node instanceof javafx.scene.Parent parent
                ? java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(node),
                        parent.getChildrenUnmodifiable().stream().flatMap(CatalogCardLifecycleFxTest::descendants))
                : java.util.stream.Stream.of(node);
    }

    @Test
    void aCardFollowsItsDownloadFromClickToInLibrary(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        CatalogPane pane = FxTestSupport.callOnFx(() -> pane(dir));
        try {
            FxTestSupport.runOnFx(pane::activate);
            awaitCards(pane);

            // The sampler has long since stopped itself by now — nothing was ever in flight.
            Thread.sleep(400);
            pressDownload(pane);
            awaitText(pane, "Downloading");

            // Progress keeps moving.
            state.set(new CatalogPane.CardState(
                    CatalogPane.Installed.NO, snapshot(DownloadState.DOWNLOADING, 0.87), () -> {}));
            awaitText(pane, "87%");

            // And the finished archive settles into the library without a manual refresh.
            state.set(CatalogPane.CardState.of(CatalogPane.Installed.YES));
            awaitText(pane, "In library");
            assertTrue(textOf(pane).contains("Open"), "and the button becomes Open");
        } finally {
            FxTestSupport.runOnFx(pane::deactivate);
        }
    }

    @Test
    void theSamplerStillStopsOnceNothingIsMoving(@TempDir Path dir) throws Exception {
        // The whole reason it self-stops: a Catalog left open must not hold a 4 Hz timer awake
        // forever. Lingering past the last download is a grace period, not a reprieve.
        seedCatalog(dir);
        CatalogPane pane = FxTestSupport.callOnFx(() -> pane(dir));
        try {
            FxTestSupport.runOnFx(pane::activate);
            awaitCards(pane);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (!FxTestSupport.callOnFx(pane::samplerRunningForTest)) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("the sampler never stopped with nothing in flight");
        } finally {
            FxTestSupport.runOnFx(pane::deactivate);
        }
    }
}
