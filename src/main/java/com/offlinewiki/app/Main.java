package com.offlinewiki.app;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import atlantafx.base.theme.PrimerLight;

public class Main extends Application {

    private ReaderController controller;

    @Override
    public void start(Stage stage) throws Exception {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        controller = new ReaderController(stage, getHostServices());
        Scene scene = new Scene(controller.root(), 1280, 840);
        controller.installShortcuts(scene);
        stage.setTitle("Offline Wiki");
        stage.setScene(scene);
        stage.show();

        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            controller.openZim(Path.of(args.get(0)));
        }
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
