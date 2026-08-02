package com.insula.app;

import java.util.LinkedHashMap;
import java.util.List;
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

import com.insula.config.Settings;
import com.insula.download.TorrentTransport;
import com.insula.download.TransportSelector;
import com.insula.library.UpdateReplacement;
import com.insula.reader.ReaderTheme;

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
    private final ComboBox<String> readerModeCombo = new ComboBox<>();
    private final Spinner<Integer> readerWidthSpinner =
            new Spinner<>(ReaderTheme.MIN_WIDTH, ReaderTheme.MAX_WIDTH, 900, 50);
    private final CheckBox rememberPositionCheck = new CheckBox("Return to where I left off in an article");
    private final CheckBox videoTranscodeCheck = new CheckBox("Play videos in the app (converts with ffmpeg)");
    private final Label videoStatus = new Label();
    private final CheckBox torrentCheck = new CheckBox(
            "Prefer BitTorrent for archives over " + Formats.bytes(TransportSelector.DEFAULT_TORRENT_THRESHOLD));
    private final CheckBox seedingCheck = new CheckBox("Share downloaded archives with others (seeding)");
    private final ComboBox<UpdateReplacement.Policy> updatePolicyCombo = new ComboBox<>();
    private final Spinner<Integer> concurrentSpinner = new Spinner<>(
            Settings.MIN_CONCURRENT_DOWNLOADS,
            Settings.MAX_CONCURRENT_DOWNLOADS,
            Settings.DEFAULT_CONCURRENT_DOWNLOADS,
            1);
    private final ListView<Object> sidebar = new ListView<>();
    private final StackPane content = new StackPane();
    private java.nio.file.Path archivesFolder;

    ListView<Object> sidebarForTest() {
        return sidebar;
    }

    boolean pageEmptyForTest() {
        return content.getChildren().isEmpty();
    }

    private Runnable onRevealArchives = () -> {};

    /** Where archives live and how to open that folder; supplied by the controller. */
    void setArchivesFolder(java.nio.file.Path folder, Runnable onReveal) {
        this.archivesFolder = folder;
        this.onRevealArchives = onReveal == null ? () -> {} : onReveal;
    }

    /** Guards control listeners while the dialog is being populated from settings. */
    private boolean loading;

    /** Reports whether ffmpeg was actually found, so the checkbox is not a promise nothing keeps. */
    void setVideoToolStatus(boolean found) {
        videoStatus.setText(
                found ? "ffmpeg found — videos can play in the app" : "ffmpeg not found — videos open externally");
        videoStatus.setStyle(found ? "-fx-text-fill: -color-success-fg;" : "-fx-opacity: 0.7;");
    }

    SettingsDialog(Window owner, Settings settings, Runnable onApply) {
        this.settings = settings;
        this.onApply = onApply;
        stage.initOwner(owner);
        stage.setTitle("Settings");
        stage.setScene(new Scene(build(), 640, 460));
    }

    /** A non-selectable sidebar heading. */
    private record Group(String title) {}

    private BorderPane build() {
        Map<String, Region> pages = new LinkedHashMap<>();
        pages.put("Appearance", appearancePage());
        pages.put("Reading view", readingViewPage());
        pages.put("Reader", readerPage());
        pages.put("Archives", archivesPage());
        pages.put("Downloads", downloadsPage());
        pages.put("Updates", updatesPage());

        // The kit's grouped sidebar. Flat lists stop scaling once a category name has to carry the
        // grouping too ("Reading view" vs "Reader" tells you nothing about which holds what).
        List<Object> rows = List.of(
                new Group("Reading"),
                "Appearance",
                "Reading view",
                "Reader",
                new Group("Content"),
                "Archives",
                new Group("Network"),
                "Downloads",
                new Group("System"),
                "Updates");

        ListView<Object> sidebar = this.sidebar;
        sidebar.getItems().setAll(rows);
        sidebar.setPrefWidth(160);
        sidebar.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("settings-group-header", "settings-sidebar-item");
                if (empty || item == null) {
                    setText(null);
                    setMouseTransparent(false);
                    setFocusTraversable(true);
                    return;
                }
                if (item instanceof Group group) {
                    setText(group.title().toUpperCase(java.util.Locale.ROOT));
                    getStyleClass().add("settings-group-header");
                    // A heading is not a destination, so it cannot be clicked or arrowed onto.
                    setMouseTransparent(true);
                    setFocusTraversable(false);
                } else {
                    setText("    " + item);
                    getStyleClass().add("settings-sidebar-item");
                    setMouseTransparent(false);
                    setFocusTraversable(true);
                }
            }
        });

        StackPane content = this.content;
        content.setPadding(new Insets(16));
        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, old, category) -> {
            if (category instanceof String name) {
                content.getChildren().setAll(pages.get(name));
            }
        });
        sidebar.getSelectionModel().select(1);

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

    private Region readingViewPage() {
        readerModeCombo.getItems().setAll("Original", "Comfortable", "Dark");
        readerModeCombo.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setReaderMode(value.toLowerCase(java.util.Locale.ROOT));
                applyAndSave();
            }
        });
        readerWidthSpinner.setEditable(true);
        readerWidthSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setReaderWidth(value);
                applyAndSave();
            }
        });
        rememberPositionCheck.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setRememberPosition(selected);
                applyAndSave();
            }
        });
        videoTranscodeCheck.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setVideoTranscode(selected);
                applyAndSave();
            }
        });

        GridPane grid = grid();
        grid.addRow(0, new Label("Reader mode"), readerModeCombo);
        grid.addRow(1, new Label("Content width (px)"), readerWidthSpinner);
        VBox box = new VBox(
                12,
                grid,
                note("Dark mode restyles the article over whatever the archive shipped, including its "
                        + "tables and inline colours. Images are dimmed rather than inverted, so photographs "
                        + "and maps still look right."),
                rememberPositionCheck,
                new javafx.scene.control.Separator(),
                videoTranscodeCheck,
                videoStatus,
                note("Archives store video as WebM, which this app cannot decode directly. With ffmpeg "
                        + "installed, a video is converted once (about 20 seconds for a 25-minute talk, then "
                        + "cached) and plays in the page. Without it, videos open in your usual player instead."));
        return page("Reading view", box);
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

        // What the user needs to predict their own downloads: BitTorrent applies only above the
        // size threshold, only when the native library loaded, and silently yields to HTTP when it
        // cannot get moving. Saying only "prefer BitTorrent" makes the setting look broken on the
        // (common) small archive that quietly went over HTTP.
        boolean torrentAvailable = TorrentTransport.isAvailable();
        Label torrentStatus = new Label(
                torrentAvailable
                        ? "BitTorrent support: available on this machine"
                        : "BitTorrent support: unavailable on this platform — downloads use HTTP");
        torrentStatus.setStyle(
                torrentAvailable ? "-fx-text-fill: -color-success-fg;" : "-fx-text-fill: -color-fg-muted;");
        torrentCheck.setDisable(!torrentAvailable);

        Label torrentNote = note("BitTorrent spreads load off the donated mirrors, and is used only for "
                + "archives larger than " + Formats.bytes(TransportSelector.DEFAULT_TORRENT_THRESHOLD)
                + " — smaller ones always download over HTTP, where the mirror list makes it faster anyway. "
                + "If a torrent cannot get moving, the download switches to HTTP by itself. Each download row "
                + "names the protocol it is actually using.");
        Label seedingNote = note("Seeding shares what you have downloaded with others. It is off by default "
                + "because it can consume a lot of upload bandwidth, which is costly on metered connections.");
        Label verifyNote = note("Every download is checked against the publisher's SHA-256. A file that fails "
                + "is kept aside rather than deleted, so you can retry without downloading it all again.");

        VBox box = new VBox(10, torrentCheck, torrentStatus, torrentNote, seedingCheck, seedingNote, verifyNote);
        return page("Downloads", box);
    }

    /** Where the archives live, and how to get to them. */
    private Region archivesPage() {
        Label path = new Label(archivesFolder == null ? "" : archivesFolder.toString());
        path.setWrapText(true);
        path.setStyle("-fx-font-family: monospace;");

        Button reveal = new Button("Open folder");
        reveal.setOnAction(e -> onRevealArchives.run());
        Button copy = new Button("Copy path");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(String.valueOf(archivesFolder));
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });

        // Deliberately no "Change…": relocating would either move gigabytes inside a modal or
        // split the library across two folders, and a setting that silently does the second is
        // worse than not having one. Say where they are and let the file manager do the rest.
        return page(
                "Archives",
                new VBox(
                        12,
                        new Label("Downloaded archives are kept in:"),
                        path,
                        new HBox(8, reveal, copy),
                        note("Moving this folder is not supported from here. Archives are ordinary .zim files: "
                                + "you can copy them to another machine, or hand one to someone with no internet "
                                + "at all, and they will open exactly as they do here.")));
    }

    /** What happens to the old build when a newer one arrives. */
    private Region updatesPage() {
        updatePolicyCombo.getItems().setAll(UpdateReplacement.Policy.values());
        updatePolicyCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(UpdateReplacement.Policy policy) {
                return policy == null
                        ? ""
                        : switch (policy) {
                            case ASK -> "Ask me each time";
                            case REPLACE -> "Delete the old build";
                            case KEEP -> "Keep both builds";
                        };
            }

            @Override
            public UpdateReplacement.Policy fromString(String text) {
                return null;
            }
        });
        updatePolicyCombo.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setUpdatePolicy(value);
                applyAndSave();
            }
        });

        concurrentSpinner.setEditable(true);
        concurrentSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setMaxConcurrentDownloads(value);
                applyAndSave();
            }
        });

        GridPane grid = grid();
        grid.addRow(0, new Label("When an update arrives"), updatePolicyCombo);
        grid.addRow(1, new Label("Downloads at once"), concurrentSpinner);
        return page(
                "Updates",
                new VBox(
                        12,
                        grid,
                        note("An update is a whole new archive, not a patch — a year-newer Wikipedia is another "
                                + "90 GB. Deleting the old build only ever happens after the new one passes its "
                                + "checksum, so a failed download can never cost you the copy you had."),
                        note("Past a handful, extra simultaneous downloads make each one slower without "
                                + "finishing the set any sooner: the limit is your connection, not the number "
                                + "of sockets.")));
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
            readerModeCombo.setValue(settings.getReaderMode().substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                    + settings.getReaderMode().substring(1));
            readerWidthSpinner.getValueFactory().setValue(ReaderTheme.clampWidth(settings.getReaderWidth()));
            rememberPositionCheck.setSelected(settings.isRememberPosition());
            videoTranscodeCheck.setSelected(settings.isVideoTranscode());
            updatePolicyCombo.setValue(settings.getUpdatePolicy());
            concurrentSpinner.getValueFactory().setValue(settings.getMaxConcurrentDownloads());
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
