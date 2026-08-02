package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.reader.ArticleRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's memory: reopening last sitting's tabs, and naming the archive on a tab once the
 * strip spans more than one.
 */
class ReaderSessionFxTest {

    private static ReaderController shell(Path dir) {
        Settings settings = Settings.load(dir.resolve("settings.properties"));
        Stage stage = new Stage();
        ReaderController controller = new ReaderController(stage, null, settings, dir);
        Scene scene = new Scene(controller.root(), 1000, 700);
        controller.installShortcuts(scene);
        stage.setScene(scene);
        return controller;
    }

    private static Path fixture(Path dir, String name) throws Exception {
        Path zim = dir.resolve(name);
        Files.copy(Path.of("src/test/resources/zim/nons-wikibooks.zim"), zim);
        return zim;
    }

    private static ArticleRef ref(Path zim, String path, String book) {
        return new ArticleRef(zim, path, path, book);
    }

    /** The labels the strip actually renders, chip included. */
    private static List<String> stripLabels(ReaderController controller) {
        return FxTestSupport.callOnFx(() -> controller.tabBarForTest().getTabs().stream()
                .map(t -> switch (t.getGraphic()) {
                    case Label label -> label.getText();
                    case HBox box ->
                        box.getChildren().stream()
                                .map(n -> ((Label) n).getText())
                                .reduce((a, b) -> a + " [" + b + "]")
                                .orElse("");
                    default -> t.getText();
                })
                .toList());
    }

    @Test
    void lastSittingsTabsReopenWithTheRightOneSelected(@TempDir Path dir) throws Exception {
        Path zim = fixture(dir, "a.zim");
        ReaderController first = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                first.openRefForTest(ref(zim, "Main_Page", "A"), true);
                first.openRefForTest(ref(zim, "Wikibooks", "A"), true);
            });
            assertEquals(
                    2, (int) FxTestSupport.callOnFx(() -> first.tabsForTest().count()));
        } finally {
            FxTestSupport.runOnFx(first::dispose);
        }

        ReaderController second = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(second::openLastArchiveIfEnabled);
            assertEquals(
                    2,
                    (int) FxTestSupport.callOnFx(() -> second.tabsForTest().count()),
                    "both tabs come back, not just the last archive");
            assertEquals(
                    1,
                    (int) FxTestSupport.callOnFx(() -> second.tabsForTest().activeIndex()),
                    "the tab that was showing is the one selected");
        } finally {
            FxTestSupport.runOnFx(second::dispose);
        }
    }

    @Test
    void aTabWhoseArchiveWasDeletedIsSkippedAndSaidSo(@TempDir Path dir) throws Exception {
        Path kept = fixture(dir, "kept.zim");
        Path doomed = fixture(dir, "doomed.zim");
        ReaderController first = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                first.openRefForTest(ref(doomed, "Main_Page", "Doomed"), true);
                first.openRefForTest(ref(kept, "Main_Page", "Kept"), true);
            });
        } finally {
            FxTestSupport.runOnFx(first::dispose);
        }
        Files.delete(doomed);

        ReaderController second = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(second::openLastArchiveIfEnabled);
            assertEquals(
                    1, (int) FxTestSupport.callOnFx(() -> second.tabsForTest().count()));
            assertTrue(
                    FxTestSupport.callOnFx(() -> second.statusTextForTest().contains("skipped")),
                    "reopening 1 of 2 tabs in silence would read as a bug");
        } finally {
            FxTestSupport.runOnFx(second::dispose);
        }
    }

    @Test
    void tabsNameTheirArchiveOnlyOnceTheStripSpansMoreThanOne(@TempDir Path dir) throws Exception {
        Path a = fixture(dir, "a.zim");
        Path b = fixture(dir, "b.zim");
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.openRefForTest(ref(a, "Main_Page", "Wikipedia"), true);
                controller.openRefForTest(ref(a, "Wikibooks", "Wikipedia"), true);
            });
            assertFalse(
                    String.join("", stripLabels(controller)).contains("["),
                    "one archive: a chip on every tab would repeat the same word down the strip");

            FxTestSupport.runOnFx(() -> controller.openRefForTest(ref(b, "Main_Page", "Wikibooks"), true));
            // Both fixtures carry the same metadata title, which is the case that matters: chips
            // key on the archive file, so the tabs are still marked as coming from different ones.
            List<String> labels = stripLabels(controller);
            assertTrue(labels.stream().allMatch(l -> l.contains("[")), "every tab is chipped: " + labels);
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }

    @Test
    void aBookmarkWhoseArchiveIsGoneIsGreyedRatherThanHidden(@TempDir Path dir) throws Exception {
        // Hiding it looks like the bookmark was lost; plugging the drive back in restores it.
        Path zim = fixture(dir, "a.zim");
        ReaderController controller = FxTestSupport.callOnFx(() -> shell(dir));
        try {
            FxTestSupport.runOnFx(() -> {
                controller.bookmarksForTest().addFirst(ref(zim, "Main_Page", "A"));
                controller.refreshBookmarkListForTest();
            });
            assertEquals(1, (int) FxTestSupport.callOnFx(
                    () -> controller.bookmarkListForTest().getItems().size()));
            assertTrue(FxTestSupport.callOnFx(
                    () -> controller.missingArchivesForTest().isEmpty()));

            Files.delete(zim);
            FxTestSupport.runOnFx(controller::refreshBookmarkListForTest);
            assertEquals(
                    1,
                    (int) FxTestSupport.callOnFx(
                            () -> controller.bookmarkListForTest().getItems().size()),
                    "the bookmark is still listed");
            assertTrue(
                    FxTestSupport.callOnFx(
                            () -> controller.missingArchivesForTest().contains(zim)),
                    "and is marked as not installed");
        } finally {
            FxTestSupport.runOnFx(controller::dispose);
        }
    }
}
