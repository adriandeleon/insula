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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Catalog driven through the real shell, against a pre-seeded catalog cache.
 *
 * <p>The cache metadata is stamped fresh so {@code activate()} never auto-refreshes — an FX test
 * must not depend on (or hammer) the live Kiwix catalog.
 */
class CatalogFxTest {

    /** Copies the real 7-entry fixture in as the cached feed, stamped as freshly fetched. */
    private static void seedCatalog(Path configDir) throws Exception {
        Path catalogDir = configDir.resolve("catalog");
        Files.createDirectories(catalogDir);
        Files.copy(Path.of("src/test/resources/opds/groups-sample.xml"), catalogDir.resolve("entries.xml"));
        Files.writeString(
                catalogDir.resolve("catalog.properties"),
                "fetchedAt=" + System.currentTimeMillis() + "\netag=\"seeded\"\nentryCount=7\n");
    }

    private static <T> T withShell(Path configDir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(configDir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, configDir);
            Scene scene = new Scene(controller.root(), 1100, 700);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    /** Store loading is off-thread; poll the FX side until the cards land. */
    private static void awaitCards(ReaderController controller, int minimum) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            int count = FxTestSupport.callOnFx(() -> {
                controller.catalogPaneForTest().setLanguagesForTest(Set.of()); // no facet filter
                return controller.catalogPaneForTest().renderedCardsForTest();
            });
            if (count >= minimum) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("catalog cards never rendered");
    }

    @Test
    void catalogRendersOneCardPerTitleGroupFromTheCache(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("catalog.open");
            return c;
        });
        try {
            // The 7-entry fixture holds exactly 3 title groups.
            awaitCards(controller, 3);
            int count =
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().renderedCardsForTest());
            assertEquals(3, count, "seven feed rows must collapse to three cards");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void catalogSearchFiltersInstantlyAndLocally(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("catalog.open");
            return c;
        });
        try {
            awaitCards(controller, 3);
            int filtered = FxTestSupport.callOnFx(() -> {
                controller.catalogPaneForTest().setSearchTextForTest("Wikipedia");
                return controller.catalogPaneForTest().renderedCardsForTest();
            });
            assertEquals(1, filtered, "only the Wikipedia group matches");

            int restored = FxTestSupport.callOnFx(() -> {
                controller.catalogPaneForTest().setSearchTextForTest("");
                return controller.catalogPaneForTest().renderedCardsForTest();
            });
            assertEquals(3, restored);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void freshnessChipReportsTheCacheAge(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("catalog.open");
            return c;
        });
        try {
            awaitCards(controller, 3);
            String chip =
                    FxTestSupport.callOnFx(() -> controller.catalogPaneForTest().freshnessTextForTest());
            assertTrue(chip.contains("today"), "a just-seeded cache reads as updated today: " + chip);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void catalogCommandsAreRegistered(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        withShell(dir, (controller, settings) -> {
            for (String id : new String[] {"library.search", "catalog.refresh"}) {
                assertTrue(
                        controller.commandsForTest().all().stream().anyMatch(c -> c.id().equals(id)),
                        "missing command " + id);
            }
            // catalog.refresh is deliberately NOT run here: it would hit the live catalog.
            return null;
        });
    }

    @Test
    void aCardShowsTheSameLiveStateALibraryRowWould(@TempDir Path dir) throws Exception {
        // The kit's rule: the Catalog and the Library never tell different stories about the same
        // archive. A card with a download in flight must show the download, not a Download button.
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("catalog.open");
            return c;
        });
        try {
            awaitCards(controller, 3);
            String pills = FxTestSupport.callOnFx(() -> texts(controller, javafx.scene.control.Label.class, true));
            // Nothing is downloading and nothing is installed, so no state pills are claimed.
            assertFalse(pills.contains("Verifying"), pills);
            assertFalse(pills.contains("Downloading"), pills);
            // ...and the cards really were walked, so the absence above means something.
            assertTrue(
                    FxTestSupport.callOnFx(() -> controller
                                    .catalogPaneForTest()
                                    .cardNodesForTest()
                                    .size())
                            >= 3,
                    "the assertion must be over real cards, not an empty walk");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void theDownloadButtonCarriesTheSize(@TempDir Path dir) throws Exception {
        // "nobody starts a 110 GB transfer by accident" — the commitment is the label itself.
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("catalog.open");
            return c;
        });
        try {
            awaitCards(controller, 3);
            String buttons = FxTestSupport.callOnFx(() -> texts(controller, javafx.scene.control.Button.class, false));
            assertTrue(buttons.contains("Download · "), "a size-bearing download button: " + buttons);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    /** Text of every matching control inside the rendered cards, joined for easy assertions. */
    private static String texts(ReaderController controller, Class<?> type, boolean pillsOnly) {
        StringBuilder out = new StringBuilder();
        for (javafx.scene.Node card : controller.catalogPaneForTest().cardNodesForTest()) {
            collect(card, type, pillsOnly, out);
        }
        return out.toString();
    }

    private static void collect(javafx.scene.Node node, Class<?> type, boolean pillsOnly, StringBuilder out) {
        if (type.isInstance(node) && (!pillsOnly || node.getStyleClass().contains("pill"))) {
            out.append(node instanceof javafx.scene.control.Labeled l ? l.getText() : "")
                    .append(" | ");
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, pillsOnly, out);
            }
        }
    }
}
