package com.offlinewiki.app;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.offlinewiki.config.Settings;

/**
 * Settings window (mini Editora shell): a category sidebar over per-category pages, with
 * <b>live apply</b> — every control writes its {@link Settings} field, saves, and re-applies
 * immediately. No OK/Cancel.
 */
final class SettingsDialog {

    private final Stage stage = new Stage();
    private final Settings settings;
    private final Runnable onApply;

    private final ComboBox<String> themeCombo = new ComboBox<>();
    private final Spinner<Integer> zoomSpinner = new Spinner<>(Settings.MIN_ZOOM, Settings.MAX_ZOOM, 100, 10);
    private final CheckBox reopenCheck = new CheckBox("Reopen the last archive on startup");
    private final Spinner<Integer> searchLimitSpinner =
            new Spinner<>(Settings.MIN_SEARCH_LIMIT, Settings.MAX_SEARCH_LIMIT, 40, 5);

    /** Guards control listeners while the dialog is being populated from settings. */
    private boolean loading;

    SettingsDialog(Window owner, Settings settings, Runnable onApply) {
        this.settings = settings;
        this.onApply = onApply;
        stage.initOwner(owner);
        stage.setTitle("Settings");
        stage.setScene(new Scene(build(), 560, 380));
    }

    private BorderPane build() {
        Map<String, Region> pages = new LinkedHashMap<>();
        pages.put("Appearance", appearancePage());
        pages.put("Reader", readerPage());

        ListView<String> sidebar = new ListView<>();
        sidebar.getItems().setAll(pages.keySet());
        sidebar.setPrefWidth(140);

        StackPane content = new StackPane();
        content.setPadding(new Insets(16));
        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, old, category) -> {
            if (category != null) {
                content.getChildren().setAll(pages.get(category));
            }
        });
        sidebar.getSelectionModel().select(0);

        Button close = new Button("Close");
        close.setOnAction(e -> stage.hide());
        HBox footer = new HBox(close);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(8, 16, 12, 16));

        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(content);
        root.setBottom(footer);
        return root;
    }

    private Region appearancePage() {
        themeCombo.getItems().setAll("Light", "Dark");
        themeCombo.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setTheme("Dark".equals(value) ? Settings.THEME_DARK : Settings.THEME_LIGHT);
                applyAndSave();
            }
        });
        zoomSpinner.setEditable(true);
        zoomSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setZoomPercent(value);
                applyAndSave();
            }
        });
        GridPane grid = grid();
        grid.addRow(0, new Label("Theme"), themeCombo);
        grid.addRow(1, new Label("Content zoom (%)"), zoomSpinner);
        return page("Appearance", grid);
    }

    private Region readerPage() {
        reopenCheck.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setReopenLastArchive(selected);
                applyAndSave();
            }
        });
        searchLimitSpinner.setEditable(true);
        searchLimitSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setSearchLimit(value);
                applyAndSave();
            }
        });
        GridPane grid = grid();
        grid.addRow(0, new Label("Search results"), searchLimitSpinner);
        VBox box = new VBox(12, reopenCheck, grid);
        return page("Reader", box);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        return grid;
    }

    private static Region page(String title, Region body) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 1.2em; -fx-font-weight: bold;");
        VBox box = new VBox(14, heading, body);
        return box;
    }

    private void applyAndSave() {
        settings.save();
        onApply.run();
    }

    /** Re-populates the controls from the live settings (also used when a command changes them). */
    void sync() {
        loading = true;
        try {
            themeCombo.setValue(settings.isDark() ? "Dark" : "Light");
            zoomSpinner.getValueFactory().setValue(settings.getZoomPercent());
            reopenCheck.setSelected(settings.isReopenLastArchive());
            searchLimitSpinner.getValueFactory().setValue(settings.getSearchLimit());
        } finally {
            loading = false;
        }
    }

    void show() {
        sync();
        stage.show();
        stage.toFront();
    }
}
