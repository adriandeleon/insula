package com.insula.app;

import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;

import com.insula.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hiding and moving the reader sidebar, and remembering where it was put. */
class SidebarLayoutFxTest {

    private static <T> T withShell(Path dir, java.util.function.BiFunction<ReaderController, Settings, T> body) {
        return FxTestSupport.callOnFx(() -> {
            Settings settings = Settings.load(dir.resolve("settings.properties"));
            Stage stage = new Stage();
            ReaderController controller = new ReaderController(stage, null, settings, dir);
            Scene scene = new Scene(controller.root(), 1000, 700);
            controller.installShortcuts(scene);
            stage.setScene(scene);
            try {
                return body.apply(controller, settings);
            } finally {
                controller.dispose();
            }
        });
    }

    @Test
    void hidingTheSidebarRemovesItRatherThanBlankingIt(@TempDir Path dir) {
        // A SplitPane keeps a divider for an invisible item, so merely hiding the node leaves a
        // dead strip and a draggable divider that moves nothing.
        withShell(dir, (controller, settings) -> {
            SplitPane split = controller.readerSplitForTest();
            assertEquals(2, split.getItems().size());

            controller.commandsForTest().run("view.toggleSidebar");
            assertEquals(1, split.getItems().size(), "the divider goes with it");

            controller.commandsForTest().run("view.toggleSidebar");
            assertEquals(2, split.getItems().size());
            return null;
        });
    }

    @Test
    void movingItRightPutsItAfterTheArticleAndMirrorsTheDivider(@TempDir Path dir) {
        // The divider is a fraction of the whole, so a naive move leaves a quarter-width article.
        withShell(dir, (controller, settings) -> {
            SplitPane split = controller.readerSplitForTest();
            Object sidebar = split.getItems().getFirst();
            double leftDivider = split.getDividerPositions()[0];

            controller.commandsForTest().run("view.sidebarSide");
            assertSame(sidebar, split.getItems().getLast(), "the sidebar is now the trailing item");
            assertEquals(
                    1 - leftDivider,
                    split.getDividerPositions()[0],
                    0.001,
                    "and takes the same share of the width with it");
            return null;
        });
    }

    @Test
    void movingAHiddenSidebarRevealsIt(@TempDir Path dir) {
        // Otherwise "move to the other side" is a silent no-op, which reads as a broken button.
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("view.toggleSidebar");
            assertEquals(1, controller.readerSplitForTest().getItems().size());

            controller.commandsForTest().run("view.sidebarSide");
            assertEquals(2, controller.readerSplitForTest().getItems().size());
            assertTrue(settings.isSidebarVisible());
            return null;
        });
    }

    @Test
    void wherePutItStaysAcrossARestart(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("view.sidebarSide");
            return null;
        });
        assertTrue(Settings.load(dir.resolve("settings.properties")).isSidebarOnRight());

        withShell(dir, (controller, settings) -> {
            assertSame(
                    controller.sidebarHostForTest(),
                    controller.readerSplitForTest().getItems().getLast(),
                    "the next launch builds it on the side it was left on");
            return null;
        });
    }
}
