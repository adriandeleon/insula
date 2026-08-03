package com.insula.app;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.insula.config.Settings;

public class Main extends Application {

    private ReaderController controller;

    @Override
    public void start(Stage stage) throws Exception {
        Path configDir = configDir();
        // Now that the config dir is known, the capture gets somewhere to survive a crash.
        DebugLog.attachFile(configDir);
        Settings settings = Settings.load(configDir.resolve("settings.properties"));
        controller = new ReaderController(stage, getHostServices(), settings, configDir);
        Scene scene = new Scene(controller.root(), 1280, 840);
        controller.installShortcuts(scene);
        stage.setTitle("Insula");
        AppIcon.applyTo(stage);
        stage.setScene(scene);
        stage.show();

        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            controller.openZim(Path.of(args.get(0)));
        } else {
            controller.openLastArchiveIfEnabled();
        }
        // Library is home: with nothing open, land there instead of an empty reader.
        controller.landOnLibraryIfIdle();
    }

    /** {@code $INSULA_CONFIG_DIR}, else {@code ~/.insula}. */
    private static Path configDir() {
        String override = System.getenv("INSULA_CONFIG_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".insula");
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.dispose();
        }
    }

    public static void main(String[] args) {
        // Must be the first statement, before any AWT class loads. Decoding WebP images (see
        // server/WebpTranscoder) goes through ImageIO, i.e. java.desktop; on macOS the AWT/Java2D
        // native pipeline contends with JavaFX for the single AppKit run loop and can hang the
        // app. Headless Java2D decodes purely in software, so the images still render.
        System.setProperty("java.awt.headless", "true");
        // Before anything else can log: a packaged bundle has no visible stderr, so an error the
        // app already explained is otherwise lost.
        DebugLog.install();
        launch(args);
    }
}
