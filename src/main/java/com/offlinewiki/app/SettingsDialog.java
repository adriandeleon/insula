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
    private final CheckBox torrentCheck = new CheckBox("Prefer BitTorrent for large archives");
    private final CheckBox seedingCheck = new CheckBox("Share downloaded archives with others (seeding)");

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
        pages.put("Downloads", downloadsPage());

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

    private Region downloadsPage() {
        torrentCheck.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setTorrentEnabled(selected);
                applyAndSave();
            }
        });
        seedingCheck.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setSeedingEnabled(selected);
                applyAndSave();
            }
        });

        Label torrentNote = note("Large archives can be fetched over BitTorrent, which spreads load off the "
                + "donated mirrors. No torrent transport is installed yet, so downloads use HTTP either way. "
                + "HTTP always remains available as a fallback — BitTorrent is blocked on many school and "
                + "office networks.");
        Label seedingNote = note("Seeding shares what you have downloaded with others. It is off by default "
                + "because it can consume a lot of upload bandwidth, which is costly on metered connections.");
        Label verifyNote = note("Every download is checked against the publisher's SHA-256. A file that fails "
                + "is kept aside rather than deleted, so you can retry without downloading it all again.");

        VBox box = new VBox(10, torrentCheck, torrentNote, seedingCheck, seedingNote, verifyNote);
        return page("Downloads", box);
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-opacity: 0.7;");
        label.setMaxWidth(360);
        return label;
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
            torrentCheck.setSelected(settings.isTorrentEnabled());
            seedingCheck.setSelected(settings.isSeedingEnabled());
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
