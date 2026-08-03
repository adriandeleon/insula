package com.insula.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Shows what {@link DebugLog} has captured.
 *
 * <p>Read-only and monospaced, because the audience for this is someone about to paste it into a
 * bug report. Export writes the same text to a file they choose rather than making them find the
 * session log in the config folder.
 */
final class DebugLogWindow {

    private final Stage stage = new Stage();
    private final TextArea text = new TextArea();
    private final Label footer = new Label();
    private final Path configDir;

    DebugLogWindow(Window owner, Path configDir) {
        this.configDir = configDir;
        stage.initOwner(owner);
        stage.initModality(Modality.NONE); // watchable while the app carries on misbehaving
        stage.setTitle("Debug Log — Insula");

        text.setEditable(false);
        text.setWrapText(false);
        text.getStyleClass().add("debug-log-text");

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> load());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button export = new Button("Export…");
        export.setOnAction(e -> export());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            DebugLog.clear();
            load();
        });
        Button close = new Button("Close");
        close.setOnAction(e -> stage.hide());

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        footer.getStyleClass().add("srow-desc");
        HBox bar = new HBox(8, refresh, copy, export, clear, gap, footer, close);
        bar.setPadding(new Insets(8));

        BorderPane root = new BorderPane(text);
        root.setBottom(bar);
        Scene scene = new Scene(root, 900, 560);
        scene.getStylesheets().add(getClass().getResource("insula.css").toExternalForm());
        stage.setScene(scene);
    }

    void show() {
        load();
        stage.show();
        stage.toFront();
    }

    private void load() {
        String snapshot = DebugLog.snapshot();
        text.setText(snapshot.isEmpty() ? "Nothing has been logged this session." : snapshot);
        text.positionCaret(text.getLength());
        Path session = DebugLog.sessionFile(configDir);
        footer.setText(session == null ? "" : "Also written to " + Formats.collapseHome(session.toString()));
    }

    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("insula-debug.log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt"));
        java.io.File chosen = chooser.showSaveDialog(stage);
        if (chosen == null) {
            return;
        }
        try {
            Files.writeString(chosen.toPath(), text.getText(), StandardCharsets.UTF_8);
            footer.setText("Exported to " + Formats.collapseHome(chosen.toString()));
        } catch (IOException e) {
            footer.setText("Could not export: " + e.getMessage());
        }
    }
}
