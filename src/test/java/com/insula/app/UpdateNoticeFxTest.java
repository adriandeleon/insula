package com.insula.app;

import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;
import com.insula.update.ReleaseInfo;
import com.insula.update.ReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The update notice in the window: when it shows, and when it stops.
 *
 * <p>Outcomes are fed in directly rather than fetched, so none of this touches the network —
 * {@code ReleaseServiceTest} covers the fetching against a real server.
 */
class UpdateNoticeFxTest {

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

    private static ReleaseService.Outcome found(String version) {
        return new ReleaseService.Outcome(
                new ReleaseInfo(version, "https://example.invalid/r", "Insula " + version), null);
    }

    @Test
    void theNoticeAppearsOnlyOnceThereIsSomethingToSay(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertFalse(controller.updateNoticeForTest().isVisible(), "nothing found yet");
            assertFalse(controller.updateNoticeForTest().isManaged(), "and it takes no room");

            controller.onUpdateOutcomeForTest(found("0.9.0"), false);

            assertTrue(controller.updateNoticeForTest().isVisible());
            assertTrue(controller.updateNoticeForTest().getText().contains("0.9.0"));
            return null;
        });
    }

    @Test
    void aBackgroundCheckThatFindsNothingSaysNothing(@TempDir Path dir) {
        // Nobody asked. An unprompted "you are up to date" is the kind of notice people turn off.
        withShell(dir, (controller, settings) -> {
            String before = controller.statusTextForTest();
            controller.onUpdateOutcomeForTest(new ReleaseService.Outcome(null, null), false);
            assertEquals(before, controller.statusTextForTest());

            controller.onUpdateOutcomeForTest(new ReleaseService.Outcome(null, "boom"), false);
            assertEquals(before, controller.statusTextForTest(), "and a failure is not their problem either");
            return null;
        });
    }

    @Test
    void askingDirectlyAlwaysGetsAnAnswer(@TempDir Path dir) {
        // The same two outcomes, but now they were a question.
        withShell(dir, (controller, settings) -> {
            controller.onUpdateOutcomeForTest(new ReleaseService.Outcome(null, null), true);
            assertTrue(controller.statusTextForTest().contains("up to date"));

            controller.onUpdateOutcomeForTest(new ReleaseService.Outcome(null, "GitHub answered 403"), true);
            assertTrue(controller.statusTextForTest().contains("403"));
            return null;
        });
    }

    @Test
    void openingTheReleasePageStopsTheNoticeForThatVersionOnly(@TempDir Path dir) {
        // Someone who went to the download page has dealt with this release; a notice that stays
        // after being acted on is the part of an update prompt people resent. The next one still
        // gets through, because what is recorded is a version and not a flag.
        withShell(dir, (controller, settings) -> {
            controller.onUpdateOutcomeForTest(found("0.9.0"), false);
            assertTrue(controller.updateNoticeForTest().isVisible());

            controller.commandsForTest().run("update.openDownloadPage");

            assertFalse(controller.updateNoticeForTest().isVisible());
            assertEquals("0.9.0", settings.getDismissedUpdateVersion());

            controller.onUpdateOutcomeForTest(found("1.0.0"), false);
            assertTrue(controller.updateNoticeForTest().isVisible(), "a later release is announced again");
            return null;
        });
    }

    @Test
    void aDismissedVersionStaysDismissedAcrossARestart(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            controller.onUpdateOutcomeForTest(found("0.9.0"), false);
            controller.commandsForTest().run("update.openDownloadPage");
            return null;
        });

        withShell(dir, (controller, settings) -> {
            assertEquals("0.9.0", settings.getDismissedUpdateVersion());
            controller.onUpdateOutcomeForTest(found("0.9.0"), false);
            assertFalse(controller.updateNoticeForTest().isVisible(), "the same release, already dealt with");
            return null;
        });
    }

    @Test
    void noBackgroundCheckRunsFromATestBuild(@TempDir Path dir) {
        // Weak on its own, and deliberately kept narrow: every gate on the background check lives
        // in ReleaseCheck.shouldCheckInBackground, where each can be tested separately. All this
        // asserts is that building a window does not go to the network, since a test build is a
        // snapshot — the stamp is untouched, and a check that ran would have written it.
        withShell(dir, (controller, settings) -> {
            assertEquals(0, settings.getLastUpdateCheckEpoch());
            return null;
        });
    }

    @Test
    void turningTheCheckOffIsRememberedAndReversible(@TempDir Path dir) {
        withShell(dir, (controller, settings) -> {
            assertTrue(settings.isUpdateCheck(), "on by default");
            controller.commandsForTest().run("view.toggleUpdateCheck");
            assertFalse(settings.isUpdateCheck());
            return null;
        });
        assertFalse(Settings.load(dir.resolve("settings.properties")).isUpdateCheck());

        withShell(dir, (controller, settings) -> {
            controller.commandsForTest().run("view.toggleUpdateCheck");
            assertTrue(settings.isUpdateCheck());
            return null;
        });
    }
}
