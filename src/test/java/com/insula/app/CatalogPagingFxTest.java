package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog paging and language names, driven through the real pane against a seeded catalog. */
class CatalogPagingFxTest {

    private static void seedCatalog(Path configDir, String fixture, int count) throws Exception {
        Path catalogDir = configDir.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.copy(Path.of("src/test/resources/opds/" + fixture), catalogDir.resolve("entries.xml"));
        Files.writeString(
                catalogDir.resolve("catalog.properties"),
                "fetchedAt=" + System.currentTimeMillis() + "\netag=\"seeded\"\nentryCount=" + count + "\n");
    }

    /** The catalog parses off-thread, so nothing is on screen the instant the surface opens. */
    private static void awaitCards(ReaderController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            int count = FxTestSupport.callOnFx(() -> {
                controller.catalogPaneForTest().setLanguagesForTest(Set.of()); // no facet filter
                return controller.catalogPaneForTest().renderedCardsForTest();
            });
            if (count > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("catalog cards never rendered");
    }

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        stage.setScene(new Scene(controller.root(), 1100, 700));
        return controller;
    }

    @Test
    void theGridNeverShowsMoreThanAPageAtATime(@TempDir Path dir) throws Exception {
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("catalog.open"));
            awaitCards(controller);
            int shown =
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().renderedCardsForTest());
            assertTrue(shown > 0, "the fixture produced cards at all");
            assertTrue(shown <= com.insula.catalog.Paging.PAGE_SIZE, "a page is a page: " + shown + " cards rendered");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void narrowingTheFilterCannotStrandTheUserOnAnEmptyPage(@TempDir Path dir) throws Exception {
        // The failure this guards is not the arithmetic but the stale page: filter down while on
        // a later page and a naive pager renders an empty grid over a non-empty result.
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("catalog.open"));
            awaitCards(controller);
            FxTestSupport.runOnFx(() -> {
                CatalogPane pane = controller.catalogPaneForTest();
                pane.goToPageForTest(99);
                pane.setSearchTextForTest("ray");
            });
            int shown =
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().renderedCardsForTest());
            assertTrue(shown > 0, "a matching search shows its matches rather than an empty page");
            assertEquals(0, (int)
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().pageForTest()));
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void languagesAreSpelledOutRatherThanAbbreviated(@TempDir Path dir) throws Exception {
        seedCatalog(dir, "starters-sample.xml", 3);
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> controller.commandsForTest().run("catalog.open"));
            awaitCards(controller);
            String text =
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().cardTextForTest());
            assertTrue(text.contains("English"), "cards name the language: " + text);
            assertTrue(!text.matches("(?s).*\\beng\\b.*"), "and never leave the raw code showing");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
