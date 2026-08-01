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

/**
 * The Store driven through the real shell, against a pre-seeded catalog cache.
 *
 * <p>The cache metadata is stamped fresh so {@code activate()} never auto-refreshes — an FX test
 * must not depend on (or hammer) the live Kiwix catalog.
 */
class StoreFxTest {

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
                controller.storePaneForTest().setLanguagesForTest(Set.of()); // no facet filter
                return controller.storePaneForTest().renderedCardsForTest();
            });
            if (count >= minimum) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("store cards never rendered");
    }

    @Test
    void storeRendersOneCardPerTitleGroupFromTheCache(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("library.show");
            return c;
        });
        try {
            // The 7-entry fixture holds exactly 3 title groups.
            awaitCards(controller, 3);
            int count =
                    FxTestSupport.callOnFx(() -> controller.storePaneForTest().renderedCardsForTest());
            assertEquals(3, count, "seven feed rows must collapse to three cards");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void storeSearchFiltersInstantlyAndLocally(@TempDir Path dir) throws Exception {
        seedCatalog(dir);
        ReaderController controller = FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController c = new ReaderController(stage, null, settings, dir);
            stage.setScene(new Scene(c.root(), 1100, 700));
            c.commandsForTest().run("library.show");
            return c;
        });
        try {
            awaitCards(controller, 3);
            int filtered = FxTestSupport.callOnFx(() -> {
                controller.storePaneForTest().setSearchTextForTest("Wikipedia");
                return controller.storePaneForTest().renderedCardsForTest();
            });
            assertEquals(1, filtered, "only the Wikipedia group matches");

            int restored = FxTestSupport.callOnFx(() -> {
                controller.storePaneForTest().setSearchTextForTest("");
                return controller.storePaneForTest().renderedCardsForTest();
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
            c.commandsForTest().run("library.show");
            return c;
        });
        try {
            awaitCards(controller, 3);
            String chip =
                    FxTestSupport.callOnFx(() -> controller.storePaneForTest().freshnessTextForTest());
            assertTrue(chip.contains("today"), "a just-seeded cache reads as updated today: " + chip);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void storeCommandsAreRegistered(@TempDir Path dir) throws Exception {
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
}
