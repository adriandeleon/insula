package com.offlinewiki.app;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.offlinewiki.config.Settings;

public class Main extends Application {

    private ReaderController controller;

    @Override
    public void start(Stage stage) throws Exception {
        Path configDir = configDir();
        Settings settings = Settings.load(configDir.resolve("settings.properties"));
        controller = new ReaderController(stage, getHostServices(), settings, configDir);
        Scene scene = new Scene(controller.root(), 1280, 840);
        controller.installShortcuts(scene);
        stage.setTitle("Offline Wiki");
        stage.setScene(scene);
        stage.show();

        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            controller.openZim(Path.of(args.get(0)));
        } else {
            controller.openLastArchiveIfEnabled();
        }
    }

    /** {@code $OFFLINE_WIKI_CONFIG_DIR}, else {@code ~/.offline-wiki}. */
    private static Path configDir() {
        String override = System.getenv("OFFLINE_WIKI_CONFIG_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".offline-wiki");
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
