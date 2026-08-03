package com.insula.app;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.library.LibraryEntry;
import com.insula.library.Shelf;
import com.insula.library.ShelfColumns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Library shelf: pinning, grouping, sorting and the drag affordance, driven through the real
 * pane rather than through {@link Shelf} alone — the arrangement logic is unit-tested, but whether
 * the pane actually renders it (and remembers it across a restart) is only visible from here.
 */
class ShelfOrganizationFxTest {

    private static final long HOUR = 3_600_000L;

    private static <T> T withShell(Path configDir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(configDir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, configDir);
            Scene scene = new Scene(controller.root(), 900, 600);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    /** Three archives whose file names give them different themes, languages and publishers. */
    private static void seed(ReaderController controller, Path dir) {
        var library = controller.libraryForTest();
        library.put(new LibraryEntry(
                dir.resolve("wikipedia_en_all_mini.zim"), "Wikipedia", 900L, "a", true, 3 * HOUR, false, 0, ""));
        library.put(new LibraryEntry(
                dir.resolve("ted_mul_technology.zim"), "TED Technology", 300L, "b", true, 2 * HOUR, false, 1, ""));
        library.put(new LibraryEntry(
                dir.resolve("gutenberg_fr_all.zim"), "Gutenberg (fr)", 600L, "c", true, 1 * HOUR, false, 2, ""));
    }

    @Test
    void archivesAreGroupedByTheirThemeWithTheCatchAllLast(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();
            pane.setArrangement(Shelf.GroupBy.THEME, Shelf.SortBy.NAME);

            assertEquals(
                    List.of("Books", "Courses & talks", "Encyclopedias & reference"),
                    pane.groupTitlesForTest(),
                    "themes come from the file name for free, and sort alphabetically");
            assertEquals(3, pane.deviceRowsForTest());
            return null;
        });
    }

    @Test
    void pinnedArchivesRiseAboveEveryGrouping(@TempDir Path dir) {
        // The star is the whole favourites model, so it must outrank whatever grouping is active
        // rather than merely sorting inside one.
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();
            pane.setArrangement(Shelf.GroupBy.THEME, Shelf.SortBy.NAME);
            assertFalse(pane.groupTitlesForTest().contains(Shelf.PINNED));

            var gutenberg = controller.libraryForTest().entries().stream()
                    .filter(e -> e.fileName().startsWith("gutenberg"))
                    .findFirst()
                    .orElseThrow();
            controller.libraryForTest().replace(gutenberg.withPinned(true));
            pane.setArrangement(Shelf.GroupBy.THEME, Shelf.SortBy.NAME);

            assertEquals(Shelf.PINNED, pane.groupTitlesForTest().getFirst(), "pinned leads the shelf");
            assertEquals("Gutenberg (fr)", pane.deviceTitlesForTest().getFirst());
            assertFalse(
                    pane.groupTitlesForTest().contains("Books"),
                    "a pinned archive leaves its theme group, which then disappears as empty");
            return null;
        });
    }

    @Test
    void groupingByLanguageAndPublisherUsesTheFileNameTokens(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();

            pane.setArrangement(Shelf.GroupBy.LANGUAGE, Shelf.SortBy.NAME);
            assertEquals(List.of("en", "fr", "mul"), pane.groupTitlesForTest());

            pane.setArrangement(Shelf.GroupBy.PUBLISHER, Shelf.SortBy.NAME);
            assertEquals(List.of("Gutenberg", "TED", "Wikipedia"), pane.groupTitlesForTest());

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.NAME);
            assertEquals(List.of("All archives"), pane.groupTitlesForTest());
            return null;
        });
    }

    @Test
    void sortingReordersTheRowsWithinAGroup(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.SIZE);
            assertEquals(
                    List.of("Wikipedia", "Gutenberg (fr)", "TED Technology"),
                    pane.deviceTitlesForTest(),
                    "largest first");

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.RECENT);
            assertEquals(
                    List.of("Wikipedia", "TED Technology", "Gutenberg (fr)"),
                    pane.deviceTitlesForTest(),
                    "most recently added first");
            return null;
        });
    }

    @Test
    void dragHandlesAppearOnlyWhileCustomIsTheChosenSort(@TempDir Path dir) {
        // Offering a drag handle under a sort that would discard the arrangement is a lie about
        // what the gesture does.
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.CUSTOM);
            assertTrue(pane.dragHandlesShownForTest());

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.NAME);
            assertFalse(pane.dragHandlesShownForTest());
            return null;
        });
    }

    @Test
    void aHandArrangedOrderSurvivesSwitchingSortAwayAndBack(@TempDir Path dir) {
        // Switching to Name must not overwrite the custom order — otherwise a glance at another
        // sort silently destroys work.
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            var library = controller.libraryForTest();
            List<LibraryEntry> byHand = List.of(
                    library.entries().stream()
                            .filter(e -> e.title().startsWith("TED"))
                            .findFirst()
                            .orElseThrow(),
                    library.entries().stream()
                            .filter(e -> e.title().startsWith("Wikipedia"))
                            .findFirst()
                            .orElseThrow(),
                    library.entries().stream()
                            .filter(e -> e.title().startsWith("Gutenberg"))
                            .findFirst()
                            .orElseThrow());
            Shelf.reorder(byHand).forEach(library::replace);

            LibraryPane pane = controller.libraryPaneForTest();
            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.CUSTOM);
            List<String> arranged = pane.deviceTitlesForTest();
            assertEquals(List.of("TED Technology", "Wikipedia", "Gutenberg (fr)"), arranged);

            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.NAME);
            pane.setArrangement(Shelf.GroupBy.NONE, Shelf.SortBy.CUSTOM);
            assertEquals(arranged, pane.deviceTitlesForTest(), "the hand arrangement is still there");
            return null;
        });
    }

    @Test
    void theChosenArrangementIsRememberedAcrossARestart(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.libraryPaneForTest().setArrangement(Shelf.GroupBy.LANGUAGE, Shelf.SortBy.SIZE);
            // setArrangement is the programmatic path; the persisting listener fires on value change.
            return null;
        });
        assertEquals(
                "LANGUAGE", Settings.load(dir.resolve("settings.properties")).getLibraryGroupBy());
        assertEquals("SIZE", Settings.load(dir.resolve("settings.properties")).getLibrarySortBy());

        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            controller.libraryPaneForTest().activate();
            assertEquals(
                    List.of("en", "fr", "mul"),
                    controller.libraryPaneForTest().groupTitlesForTest(),
                    "the remembered grouping is applied on the next launch");
            return null;
        });
    }

    @Test
    void aWideWindowFlowsWholeGroupsIntoColumns(@TempDir Path dir) {
        // Whole groups move into per-column containers, so anything that reads the shelf by
        // walking its children has to keep working — the titles, and the drag handles.
        withShell(dir, (controller, settings) -> {
            seed(controller, dir);
            LibraryPane pane = controller.libraryPaneForTest();
            // Grouped by theme so there are three groups for the flow to distribute, and Custom
            // so the drag handles are expected in both layouts.
            pane.setArrangement(Shelf.GroupBy.THEME, Shelf.SortBy.CUSTOM);

            List<String> oneColumn = pane.deviceTitlesForTest();
            assertTrue(pane.dragHandlesShownForTest(), "handles before the split");
            assertEquals(1, pane.shelfColumnsForTest(), "a 900px test window is one column");
            assertEquals(3, pane.deviceRowsForTest());

            pane.setShelfWidthForTest(2 * ShelfColumns.MIN_COLUMN_WIDTH + 200);
            assertEquals(2, pane.shelfColumnsForTest(), "a wide one earns a second");
            assertEquals(3, pane.deviceRowsForTest(), "every row is still there");
            assertEquals(oneColumn, pane.deviceTitlesForTest(), "in the same order, read down the columns");
            assertEquals(
                    3,
                    pane.groupTitlesForTest().size(),
                    "every heading is still on screen, one per group, none orphaned");
            assertEquals(
                    List.of("Books", "Courses & talks", "Encyclopedias & reference"),
                    pane.groupTitlesForTest(),
                    "and a group is never torn between columns");
            assertTrue(pane.dragHandlesShownForTest(), "and the handles are still findable");
            return null;
        });
    }
}
