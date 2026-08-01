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
        Settings settings = Settings.load(configDir.resolve("settings.properties"));
        controller = new ReaderController(stage, getHostServices(), settings, configDir);
        Scene scene = new Scene(controller.root(), 1280, 840);
        controller.installShortcuts(scene);
        stage.setTitle("Insula");
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
        launch(args);
    }
}
