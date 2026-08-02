package com.insula.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import atlantafx.base.controls.ToggleSwitch;
import com.insula.config.Settings;
import com.insula.download.TorrentTransport;
import com.insula.download.TransportSelector;
import com.insula.library.UpdateReplacement;
import com.insula.reader.ReaderTheme;

/**
 * Settings, laid out to the UI kit: a grouped sidebar — Reading / Content / Network / System —
 * over per-category pages whose controls sit in hairline-divided cards, with <b>live apply</b>
 * (every control writes its {@link Settings} field, saves, and re-applies; no OK button).
 *
 * <p>Three kit rules this window keeps:
 *
 * <ul>
 *   <li><b>Groups, not a flat list.</b> A flat list stops scaling once the category name has to
 *       carry the grouping too; the group headings are structure, not destinations.
 *   <li><b>Status lines live under their control</b> — green "available on this machine", muted
 *       when absent — so a toggle is never a promise nothing keeps.
 *   <li><b>The long-form notes stay.</b> The app explaining <em>why</em> (the ffmpeg note, the
 *       BitTorrent note, the closing integrity note) is the most Insula thing about this window;
 *       Settings is where trust is either built or lost.
 * </ul>
 *
 * <p>The window attaches the same {@code insula.css} (and dark class) as the main scene — it
 * previously attached neither, so it rendered in raw defaults with none of the app's tokens, and
 * no amount of layout could have made that match the kit.
 */
final class SettingsDialog {

    private final Stage stage = new Stage();
    private final Settings settings;
    private final Runnable onApply;
    private final BorderPane root = new BorderPane();

    // -- Reading
    private final ComboBox<String> themeCombo = new ComboBox<>();
    private final Spinner<Integer> zoomSpinner = new Spinner<>(Settings.MIN_ZOOM, Settings.MAX_ZOOM, 100, 10);
    private final ComboBox<String> readerModeCombo = new ComboBox<>();
    private final Spinner<Integer> readerWidthSpinner =
            new Spinner<>(ReaderTheme.MIN_WIDTH, ReaderTheme.MAX_WIDTH, 900, 50);
    private final ToggleSwitch rememberPositionSwitch = new ToggleSwitch();
    private final Spinner<Integer> searchLimitSpinner =
            new Spinner<>(Settings.MIN_SEARCH_LIMIT, Settings.MAX_SEARCH_LIMIT, 40, 5);

    // -- Content
    private final Label archivesDesc = new Label();
    private final ToggleSwitch torrentSwitch = new ToggleSwitch();
    private final Label torrentStatus = new Label();
    private final ToggleSwitch seedingSwitch = new ToggleSwitch();
    private final ComboBox<UpdateReplacement.Policy> updatePolicyCombo = new ComboBox<>();
    private final Spinner<Integer> concurrentSpinner = new Spinner<>(
            Settings.MIN_CONCURRENT_DOWNLOADS,
            Settings.MAX_CONCURRENT_DOWNLOADS,
            Settings.DEFAULT_CONCURRENT_DOWNLOADS,
            1);
    private final Label catalogFreshness = new Label();
    private final ToggleSwitch transcodeSwitch = new ToggleSwitch();
    private final Label videoStatus = new Label();

    // -- Network
    private final Spinner<Integer> lanPortSpinner = new Spinner<>(0, 65535, 8181, 1);

    // -- System
    private final ToggleSwitch reopenSwitch = new ToggleSwitch();

    private final ListView<Object> sidebar = new ListView<>();
    private final StackPane content = new StackPane();
    private final Label configPath = new Label();

    private Path archivesFolder;
    private Runnable onRevealArchives = () -> {};
    private Path configFolder;
    private Runnable onRevealConfig = () -> {};
    private Supplier<String> catalogAge = () -> "";
    private Runnable onRefreshCatalog = () -> {};

    /** Guards control listeners while the dialog is being populated from settings. */
    private boolean loading;

    /** A non-selectable sidebar heading. */
    private record Group(String title) {}

    SettingsDialog(Window owner, Settings settings, Runnable onApply) {
        this.settings = settings;
        this.onApply = onApply;
        stage.initOwner(owner);
        stage.setTitle("Settings — Insula");
        Scene scene = new Scene(build(), 780, 560);
        scene.getStylesheets().add(getClass().getResource("insula.css").toExternalForm());
        stage.setScene(scene);
    }

    void setArchivesFolder(Path folder, Runnable onReveal) {
        this.archivesFolder = folder;
        this.onRevealArchives = onReveal == null ? () -> {} : onReveal;
    }

    void setConfigFolder(Path folder, Runnable onReveal) {
        this.configFolder = folder;
        this.onRevealConfig = onReveal == null ? () -> {} : onReveal;
    }

    /** The catalog page's facts and its one action; supplied by the controller. */
    void setCatalogInfo(Supplier<String> age, Runnable refresh) {
        this.catalogAge = age == null ? () -> "" : age;
        this.onRefreshCatalog = refresh == null ? () -> {} : refresh;
    }

    /** Reports whether ffmpeg was actually found, so the switch is not a promise nothing keeps. */
    void setVideoToolStatus(boolean found) {
        videoStatus.setText(
                found ? "ffmpeg found — videos can play in the app" : "ffmpeg not found — videos open externally");
        videoStatus.getStyleClass().removeAll("srow-status-ok", "srow-status-muted");
        videoStatus.getStyleClass().add(found ? "srow-status-ok" : "srow-status-muted");
    }

    // ---------------------------------------------------------------- structure

    private BorderPane build() {
        Map<String, Region> pages = new LinkedHashMap<>();
        pages.put("Appearance", appearancePage());
        pages.put("Reader View", readerViewPage());
        pages.put("Search", searchPage());
        pages.put("Downloads", downloadsPage());
        pages.put("Catalog", catalogPage());
        pages.put("Video playback", videoPage());
        pages.put("LAN sharing", lanPage());
        pages.put("Startup", startupPage());
        pages.put("Advanced", advancedPage());

        // The kit's exact sidebar: Reading / Content / Network / System.
        List<Object> rows = List.of(
                new Group("Reading"),
                "Appearance",
                "Reader View",
                "Search",
                new Group("Content"),
                "Downloads",
                "Catalog",
                "Video playback",
                new Group("Network"),
                "LAN sharing",
                new Group("System"),
                "Startup",
                "Advanced");

        sidebar.getItems().setAll(rows);
        sidebar.setPrefWidth(218);
        sidebar.getStyleClass().add("set-nav");
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
                    setText(String.valueOf(item));
                    getStyleClass().add("settings-sidebar-item");
                    setMouseTransparent(false);
                    setFocusTraversable(true);
                }
            }
        });

        sidebar.getSelectionModel().selectedItemProperty().addListener((obs, old, category) -> {
            if (category instanceof String name) {
                content.getChildren().setAll(pages.get(name));
            }
        });
        sidebar.getSelectionModel().select(1);

        content.setPadding(new Insets(26, 36, 34, 36));
        content.setAlignment(Pos.TOP_LEFT);
        ScrollPane page = new ScrollPane(content);
        page.setFitToWidth(true);
        page.getStyleClass().add("set-page");

        root.setLeft(sidebar);
        root.setCenter(page);
        return root;
    }

    /** Page skeleton: display title, one-line subtitle, then cards and notes. */
    private static Region page(String title, String subtitle, Region... body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("set-title");
        Label sub = new Label(subtitle);
        sub.getStyleClass().add("set-sub");
        VBox box = new VBox(14, new VBox(4, heading, sub));
        box.getChildren().addAll(body);
        box.setMaxWidth(640);
        return box;
    }

    /** A card of rows divided by hairlines — the kit's set-card / srow. */
    private static Region card(Region... rowNodes) {
        VBox box = new VBox();
        for (int i = 0; i < rowNodes.length; i++) {
            if (i > 0) {
                Region line = new Region();
                line.getStyleClass().add("srow-line");
                box.getChildren().add(line);
            }
            box.getChildren().add(rowNodes[i]);
        }
        box.getStyleClass().add("set-card");
        return box;
    }

    /**
     * One row: bold label + muted description (+ an optional live detail/status label) on the
     * left, the control on the right — the kit's srow anatomy.
     */
    private static Region row(String label, String description, Label detail, javafx.scene.Node control) {
        Label name = new Label(label);
        name.getStyleClass().add("srow-label");
        VBox main = new VBox(2, name);
        if (description != null && !description.isBlank()) {
            Label desc = new Label(description);
            desc.getStyleClass().add("srow-desc");
            desc.setWrapText(true);
            main.getChildren().add(desc);
        }
        if (detail != null) {
            detail.setWrapText(true);
            main.getChildren().add(detail);
        }
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox row = new HBox(16, main);
        if (control != null) {
            row.getChildren().add(control);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("srow");
        return row;
    }

    private static Region row(String label, String description, javafx.scene.Node control) {
        return row(label, description, null, control);
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("set-note");
        return label;
    }

    // ---------------------------------------------------------------- Reading

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
        return page(
                "Appearance",
                "Theme and text size.",
                card(
                        row("Theme", "Dark mode restyles the whole app, articles included.", themeCombo),
                        row("Content zoom (%)", "Applies to articles; the interface stays at 100%.", zoomSpinner)));
    }

    private Region readerViewPage() {
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
        rememberPositionSwitch.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setRememberPosition(selected);
                applyAndSave();
            }
        });
        return page(
                "Reader View",
                "How articles read.",
                card(
                        row("Reader mode", "Original keeps the archive's own styling.", readerModeCombo),
                        row("Content width (px)", "The article column, centered in the window.", readerWidthSpinner),
                        row(
                                "Return to where I left off",
                                "Reopening an article restores your place, with a way back to the top.",
                                rememberPositionSwitch)),
                note("Dark mode restyles the article over whatever the archive shipped, including its tables "
                        + "and inline colours. Images are dimmed rather than inverted, so photographs and maps "
                        + "still look right."));
    }

    private Region searchPage() {
        searchLimitSpinner.setEditable(true);
        searchLimitSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setSearchLimit(value);
                applyAndSave();
            }
        });
        return page(
                "Search",
                "One search across every archive you have.",
                card(row("Results to show", "Per search, across all open archives.", searchLimitSpinner)),
                note("Results are ranked by how confident the match is — exact, starts-with, word start, "
                        + "contains, then fuzzy — and each result names the tier that put it there. Search "
                        + "works fully offline; nothing leaves this machine."));
    }

    // ---------------------------------------------------------------- Content

    private Region downloadsPage() {
        torrentSwitch.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setTorrentEnabled(selected);
                applyAndSave();
            }
        });
        seedingSwitch.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setSeedingEnabled(selected);
                applyAndSave();
            }
        });
        updatePolicyCombo.getItems().setAll(UpdateReplacement.Policy.values());
        updatePolicyCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(UpdateReplacement.Policy policy) {
                return policy == null
                        ? ""
                        : switch (policy) {
                            case ASK -> "Ask before deleting the old build";
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

        // What the user needs to predict their own downloads: BitTorrent applies only above the
        // size threshold, only when the native library loaded, and silently yields to HTTP when it
        // cannot get moving. Saying only "prefer BitTorrent" makes the setting look broken on the
        // (common) small archive that quietly went over HTTP.
        boolean torrentAvailable = TorrentTransport.isAvailable();
        // Not "unavailable on this platform": the native either loaded or it did not, and for a
        // long time it did not — because of how it was packaged, on every platform equally. A
        // message that blames the OS for a build problem sends anyone reading it the wrong way.
        torrentStatus.setText(
                torrentAvailable
                        ? "BitTorrent support: available on this machine"
                        : "BitTorrent support: the native library did not load — downloads use HTTP");
        torrentStatus.getStyleClass().add(torrentAvailable ? "srow-status-ok" : "srow-status-muted");
        torrentSwitch.setDisable(!torrentAvailable);

        archivesDesc.getStyleClass().add("srow-desc");
        Button openFolder = new Button("Open folder");
        openFolder.setOnAction(e -> onRevealArchives.run());

        return page(
                "Downloads",
                "Where archives land, and which transport fetches them.",
                card(
                        row("Archives folder", null, archivesDesc, openFolder),
                        row(
                                "Prefer BitTorrent for archives over "
                                        + Formats.bytes(TransportSelector.DEFAULT_TORRENT_THRESHOLD),
                                "Spreads load off the donated mirrors. Smaller archives always use HTTP, where "
                                        + "the mirror list makes it faster anyway. If a torrent cannot get moving, "
                                        + "the download switches to HTTP by itself.",
                                torrentStatus,
                                torrentSwitch),
                        row(
                                "Share downloaded archives with others (seeding)",
                                "Off by default. Uploads only while Insula is open.",
                                seedingSwitch)),
                card(
                        row("When an update replaces an archive", null, updatePolicyCombo),
                        row("Simultaneous downloads", null, concurrentSpinner)),
                note("Every download is verified against the publisher's SHA-256 before it can open. A file "
                        + "that fails is quarantined — never deleted — and Repair uses the publisher's piece "
                        + "hashes to re-fetch only the bad pieces."));
    }

    private Region catalogPage() {
        catalogFreshness.getStyleClass().add("srow-desc");
        Button refresh = new Button("Refresh now");
        refresh.setOnAction(e -> {
            onRefreshCatalog.run();
            refreshCatalogRow();
        });
        return page(
                "Catalog",
                "The Kiwix catalog, cached so browsing works offline.",
                card(row("Catalog freshness", null, catalogFreshness, refresh)),
                note("The catalog never refreshes by itself — nothing in Insula touches the network unbidden. "
                        + "When the cached copy is more than two weeks old, the Catalog shows an amber notice "
                        + "with a refresh link instead."));
    }

    private Region videoPage() {
        transcodeSwitch.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setVideoTranscode(selected);
                applyAndSave();
            }
        });
        return page(
                "Video playback",
                "What happens when an article carries a video.",
                card(row(
                        "Play videos in the app",
                        "Converts with ffmpeg; without it, videos open in your usual player.",
                        videoStatus,
                        transcodeSwitch)),
                note("Archives store video as WebM, which this app cannot decode directly. With ffmpeg "
                        + "installed, a video is converted once (about 20 seconds for a 25-minute talk, then "
                        + "cached) and plays in the page. Without it, videos open in your usual player instead."));
    }

    // ---------------------------------------------------------------- Network

    private Region lanPage() {
        lanPortSpinner.setEditable(true);
        lanPortSpinner.valueProperty().addListener((obs, old, value) -> {
            if (!loading && value != null) {
                settings.setLanPort(value);
                applyAndSave();
            }
        });
        return page(
                "LAN sharing",
                "Serve your archives to other devices on this network.",
                card(row("Port", "0 picks a free port each time sharing starts.", lanPortSpinner)),
                note("Start sharing from the Library, which is also where you choose which archives are "
                        + "visible. Anyone on this network can read them while sharing is on; it stops when "
                        + "you close Insula and never starts by itself."));
    }

    // ---------------------------------------------------------------- System

    private Region startupPage() {
        reopenSwitch.selectedProperty().addListener((obs, old, selected) -> {
            if (!loading) {
                settings.setReopenLastArchive(selected);
                applyAndSave();
            }
        });
        return page(
                "Startup",
                "What Insula does when it opens.",
                card(row(
                        "Reopen where I left off",
                        "Restores last sitting's tabs. A tab whose archive is gone is skipped and counted, "
                                + "never restored broken.",
                        reopenSwitch)));
    }

    private Region advancedPage() {
        configPath.getStyleClass().add("srow-desc");
        Button open = new Button("Open folder");
        open.setOnAction(e -> onRevealConfig.run());
        return page(
                "Advanced",
                "The plumbing, for when something needs a look.",
                card(row("Configuration folder", null, configPath, open)),
                note("Settings, the library index, bookmarks, history and reading positions are all plain "
                        + "text files in this folder. Archives are ordinary .zim files — copy them anywhere, "
                        + "hand one to someone with no internet at all, and they open exactly as they do here."));
    }

    // ---------------------------------------------------------------- live state

    private void applyAndSave() {
        settings.save();
        onApply.run();
    }

    /** The kit's folder line: "~/.insula/archives — 96.2 GB in use, 312 GB free". */
    private void refreshArchivesRow() {
        if (archivesFolder == null) {
            archivesDesc.setText("");
            return;
        }
        String base = Formats.collapseHome(archivesFolder.toString());
        try {
            long used = 0;
            if (Files.isDirectory(archivesFolder)) {
                try (var stream = Files.list(archivesFolder)) {
                    used = stream.filter(Files::isRegularFile)
                            .mapToLong(f -> {
                                try {
                                    return Files.size(f);
                                } catch (java.io.IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }
            }
            Path probe = Files.isDirectory(archivesFolder) ? archivesFolder : archivesFolder.getParent();
            long free = Files.getFileStore(probe).getUsableSpace();
            archivesDesc.setText(base + " — " + Formats.bytes(used) + " in use, " + Formats.bytes(free) + " free");
        } catch (java.io.IOException | RuntimeException e) {
            archivesDesc.setText(base);
        }
    }

    private void refreshCatalogRow() {
        catalogFreshness.setText(catalogAge.get());
    }

    /** Re-populates the controls from the live settings (also used when a command changes them). */
    void sync() {
        loading = true;
        try {
            themeCombo.setValue(settings.isDark() ? "Dark" : "Light");
            zoomSpinner.getValueFactory().setValue(settings.getZoomPercent());
            readerModeCombo.setValue(settings.getReaderMode().substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                    + settings.getReaderMode().substring(1));
            readerWidthSpinner.getValueFactory().setValue(ReaderTheme.clampWidth(settings.getReaderWidth()));
            rememberPositionSwitch.setSelected(settings.isRememberPosition());
            searchLimitSpinner.getValueFactory().setValue(settings.getSearchLimit());
            torrentSwitch.setSelected(settings.isTorrentEnabled());
            seedingSwitch.setSelected(settings.isSeedingEnabled());
            updatePolicyCombo.setValue(settings.getUpdatePolicy());
            concurrentSpinner.getValueFactory().setValue(settings.getMaxConcurrentDownloads());
            transcodeSwitch.setSelected(settings.isVideoTranscode());
            lanPortSpinner.getValueFactory().setValue(settings.getLanPort());
            reopenSwitch.setSelected(settings.isReopenLastArchive());
            configPath.setText(configFolder == null ? "" : Formats.collapseHome(configFolder.toString()));
            refreshArchivesRow();
            refreshCatalogRow();
            // The dialog is its own scene, so the dark class must track the theme here too.
            var classes = root.getStyleClass();
            if (settings.isDark() && !classes.contains("insula-dark")) {
                classes.add("insula-dark");
            } else if (!settings.isDark()) {
                classes.remove("insula-dark");
            }
        } finally {
            loading = false;
        }
    }

    void show() {
        sync();
        stage.show();
        stage.toFront();
    }

    // ------------------------------------------------------- package-visible test seams

    ListView<Object> sidebarForTest() {
        return sidebar;
    }

    boolean pageEmptyForTest() {
        return content.getChildren().isEmpty();
    }
}
