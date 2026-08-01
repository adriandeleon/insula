package com.offlinewiki.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.offlinewiki.catalog.CatalogClient;
import com.offlinewiki.command.CommandRegistry;
import com.offlinewiki.command.Keybindings;
import com.offlinewiki.config.Settings;
import com.offlinewiki.download.DownloadManager;
import com.offlinewiki.download.HttpMultiSourceTransport;
import com.offlinewiki.download.TransportSelector;
import com.offlinewiki.library.Library;
import com.offlinewiki.server.ZimHttpServer;
import com.offlinewiki.zim.Dirent;
import com.offlinewiki.zim.ZimArchive;

/**
 * The reader shell: toolbar, a search-as-you-type sidebar over the archive's title index, and a
 * WebView reading pane fed by the loopback {@link ZimHttpServer}.
 *
 * <p>Every user-facing action is a {@link com.offlinewiki.command.Command} in the registry — the
 * toolbar, the keybindings and the command palette all dispatch through it, so nothing is
 * reachable by mouse alone. Preferences live in {@link Settings} and apply live via
 * {@link #applySettings()}.
 */
final class ReaderController {

    private final Stage stage;
    private final HostServices hostServices;
    private final Settings settings;

    private final StackPane root = new StackPane();
    private final BorderPane shell = new BorderPane();
    private final TextField searchField = new TextField();
    private final ListView<ZimArchive.SearchResult> results = new ListView<>();
    private final WebView webView = new WebView();
    private final Button backButton = new Button("←");
    private final Button forwardButton = new Button("→");
    private final Button homeButton = new Button("Home");
    private final Label status = new Label("Open a .zim archive to start reading");

    private final CommandRegistry commands = new CommandRegistry();
    private final Keybindings keys = new Keybindings();
    private final CommandPalette palette;
    private SettingsDialog settingsDialog;

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zim-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong searchGeneration = new AtomicLong();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(150));

    private ZimHttpServer server;
    private ZimArchive archive;
    private String token;
    private String bookTitle = "";

    private final CatalogClient catalog = new CatalogClient();
    private final Library library;
    private final TransportSelector transports;
    private final DownloadManager downloads;
    private final LibraryPane libraryPane;
    private SplitPane readerSplit;
    private boolean showingLibrary;

    ReaderController(Stage stage, HostServices hostServices, Settings settings, Path dataDir) {
        this.stage = stage;
        this.hostServices = hostServices;
        this.settings = settings;
        this.library = Library.load(dataDir.resolve("library.properties"));
        this.transports = new TransportSelector(new HttpMultiSourceTransport());
        this.downloads = new DownloadManager(transports, library, dataDir.resolve("archives"));
        this.libraryPane = new LibraryPane(catalog, downloads, library, this::openFromLibrary, status::setText);
        buildUi();
        registerCommands();
        bindKeys();
        palette = new CommandPalette(root, commands, keys::displayFor);
        applySettings();
    }

    Parent root() {
        return root;
    }

    // ------------------------------------------------------- package-visible test seams

    CommandRegistry commandsForTest() {
        return commands;
    }

    Keybindings keybindingsForTest() {
        return keys;
    }

    WebView webViewForTest() {
        return webView;
    }

    void searchForTest(String query) {
        runSearch(query);
    }

    boolean hasArchiveForTest() {
        return archive != null;
    }

    LibraryPane libraryPaneForTest() {
        return libraryPane;
    }

    boolean libraryShowingForTest() {
        return showingLibrary;
    }

    // ---------------------------------------------------------------- commands

    private void registerCommands() {
        commands.register("file.open", "Open ZIM Archive…", this::chooseAndOpen);
        commands.register("view.commandPalette", "Show Command Palette", () -> palette.toggle());
        commands.register("view.settings", "Open Settings", this::showSettings);
        commands.register("search.focus", "Focus Search", () -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
        commands.register("nav.back", "Go Back", () -> historyGo(-1));
        commands.register("nav.forward", "Go Forward", () -> historyGo(1));
        commands.register("nav.home", "Go to Main Page", this::goHome);
        commands.register("view.toggleTheme", "Toggle Light/Dark Theme", this::toggleTheme);
        commands.register("view.zoomIn", "Zoom In", () -> adjustZoom(10));
        commands.register("view.zoomOut", "Zoom Out", () -> adjustZoom(-10));
        commands.register("view.zoomReset", "Reset Zoom", () -> setZoom(100));
        commands.register("view.toggleReopenLast", "Toggle: Reopen Last Archive on Startup", () -> {
            settings.setReopenLastArchive(!settings.isReopenLastArchive());
            saveAndApply();
            status.setText("Reopen last archive on startup: " + (settings.isReopenLastArchive() ? "on" : "off"));
        });
        commands.register("library.show", "Show Library and Downloads", this::showLibrary);
        commands.register("library.reader", "Back to Reader", this::showReader);
        commands.register("library.toggle", "Toggle Library / Reader", this::toggleLibrary);
        commands.register("library.search", "Search the Online Catalog", () -> {
            showLibrary();
            libraryPane.focusSearch();
        });
        commands.register("download.cancelAll", "Cancel All Downloads", () -> {
            downloads.jobs().forEach(DownloadManager.Job::cancel);
            status.setText("Cancelled all downloads");
        });
        commands.register("view.toggleTorrent", "Toggle: Prefer BitTorrent for Large Archives", () -> {
            settings.setTorrentEnabled(!settings.isTorrentEnabled());
            saveAndApply();
            status.setText("Prefer BitTorrent for large archives: " + (settings.isTorrentEnabled() ? "on" : "off")
                    + " (no torrent transport installed yet — HTTP is used either way)");
        });
        commands.register("app.quit", "Quit", () -> stage.close());
    }

    private void bindKeys() {
        keys.bind(
                "view.commandPalette",
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        keys.bind("file.open", new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.settings", new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        keys.bind("search.focus", new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN));
        keys.bind("nav.back", new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN));
        keys.bind("nav.forward", new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN));
        keys.bind("view.zoomIn", new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.zoomOut", new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.zoomReset", new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN));
        keys.bind("library.toggle", new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
    }

    void installShortcuts(Scene scene) {
        keys.install(scene, commands);
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        Button openButton = new Button("Open…");
        openButton.setOnAction(e -> commands.run("file.open"));
        backButton.setOnAction(e -> commands.run("nav.back"));
        forwardButton.setOnAction(e -> commands.run("nav.forward"));
        homeButton.setOnAction(e -> commands.run("nav.home"));
        backButton.setDisable(true);
        forwardButton.setDisable(true);
        homeButton.setDisable(true);

        Button libraryButton = new Button("Library");
        libraryButton.setTooltip(new javafx.scene.control.Tooltip("Browse and download archives"));
        libraryButton.setOnAction(e -> commands.run("library.toggle"));
        Button paletteButton = new Button("⌘K");
        paletteButton.setTooltip(new javafx.scene.control.Tooltip("Command palette"));
        paletteButton.setOnAction(e -> commands.run("view.commandPalette"));
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> commands.run("view.settings"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolbar = new ToolBar(
                openButton,
                libraryButton,
                new Separator(),
                backButton,
                forwardButton,
                homeButton,
                spacer,
                paletteButton,
                settingsButton);

        searchField.setPromptText("Search titles (Ctrl+L)");
        searchField.textProperty().addListener((obs, old, text) -> {
            searchDebounce.setOnFinished(e -> runSearch(text));
            searchDebounce.playFromStart();
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN && !results.getItems().isEmpty()) {
                results.getSelectionModel().select(0);
                results.requestFocus();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && !results.getItems().isEmpty()) {
                openResult(results.getItems().get(0));
                e.consume();
            }
        });

        results.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ZimArchive.SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        });
        results.setOnMouseClicked(e -> {
            ZimArchive.SearchResult selected = results.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openResult(selected);
            }
        });
        results.setOnKeyPressed(e -> {
            ZimArchive.SearchResult selected = results.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selected != null) {
                openResult(selected);
                e.consume();
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);

        VBox sidebar = new VBox(8, searchField, results);
        sidebar.setPadding(new Insets(8));
        sidebar.setMinWidth(220);
        sidebar.setPrefWidth(300);

        readerSplit = new SplitPane(sidebar, webView);
        readerSplit.setOrientation(Orientation.HORIZONTAL);
        readerSplit.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);

        WebEngine engine = webView.getEngine();
        engine.locationProperty().addListener((obs, old, location) -> onLocationChanged(location));
        engine.getHistory().currentIndexProperty().addListener((obs, old, idx) -> updateNavButtons());
        engine.getHistory().getEntries().addListener((javafx.collections.ListChangeListener<WebHistory.Entry>)
                c -> updateNavButtons());

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(4, 10, 4, 10));

        shell.setTop(toolbar);
        shell.setCenter(readerSplit);
        shell.setBottom(statusBar);
        root.getChildren().add(shell);
    }

    // ---------------------------------------------------------------- library / downloads

    private void showLibrary() {
        if (!showingLibrary) {
            shell.setCenter(libraryPane.node());
            libraryPane.activate();
            showingLibrary = true;
        }
    }

    private void showReader() {
        if (showingLibrary) {
            libraryPane.deactivate();
            shell.setCenter(readerSplit);
            showingLibrary = false;
        }
    }

    private void toggleLibrary() {
        if (showingLibrary) {
            showReader();
        } else {
            showLibrary();
        }
    }

    /** Opens a downloaded archive: switches back to the reader and loads it. */
    private void openFromLibrary(Path file) {
        showReader();
        openZim(file);
    }

    // ---------------------------------------------------------------- settings

    /** Pushes every preference onto the live UI. Called at startup and after each settings change. */
    void applySettings() {
        Application.setUserAgentStylesheet(
                settings.isDark()
                        ? new PrimerDark().getUserAgentStylesheet()
                        : new PrimerLight().getUserAgentStylesheet());
        webView.setZoom(settings.getZoomPercent() / 100.0);
        transports.setTorrentEnabled(settings.isTorrentEnabled());
        if (settingsDialog != null) {
            settingsDialog.sync();
        }
    }

    private void saveAndApply() {
        settings.save();
        applySettings();
    }

    private void showSettings() {
        if (settingsDialog == null) {
            settingsDialog = new SettingsDialog(stage, settings, this::applySettings);
        }
        settingsDialog.show();
    }

    private void toggleTheme() {
        settings.setTheme(settings.isDark() ? Settings.THEME_LIGHT : Settings.THEME_DARK);
        saveAndApply();
        status.setText("Theme: " + settings.getTheme());
    }

    private void adjustZoom(int deltaPercent) {
        setZoom(settings.getZoomPercent() + deltaPercent);
    }

    private void setZoom(int percent) {
        settings.setZoomPercent(percent);
        saveAndApply();
        status.setText("Zoom: " + settings.getZoomPercent() + "%");
    }

    // ---------------------------------------------------------------- archive lifecycle

    private void chooseAndOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open ZIM archive");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIM archives", "*.zim"));
        String last = settings.getLastArchive();
        if (!last.isBlank()) {
            Path parent = Path.of(last).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setInitialDirectory(parent.toFile());
            }
        }
        java.io.File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            openZim(chosen.toPath());
        }
    }

    /** Opens the archive remembered by {@link Settings#getLastArchive()}, if enabled and still present. */
    void openLastArchiveIfEnabled() {
        String last = settings.getLastArchive();
        if (settings.isReopenLastArchive() && !last.isBlank() && Files.isRegularFile(Path.of(last))) {
            openZim(Path.of(last));
        }
    }

    void openZim(Path file) {
        try {
            if (server == null) {
                server = new ZimHttpServer();
            }
            closeArchive();
            archive = ZimArchive.open(file);
            token = server.register(archive);
            bookTitle = archive.metadata("Title").orElse(file.getFileName().toString());
            stage.setTitle(bookTitle + " — Offline Wiki");
            status.setText(bookTitle + " · " + archive.entryCount() + " entries · " + file);
            homeButton.setDisable(false);
            results.getItems().clear();
            searchField.clear();
            settings.setLastArchive(file.toAbsolutePath().toString());
            settings.save();
            goHome();
            searchField.requestFocus();
        } catch (IOException e) {
            status.setText("Failed to open " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private void closeArchive() {
        if (archive != null) {
            if (token != null) {
                server.unregister(token);
            }
            try {
                archive.close();
            } catch (IOException ignored) {
                // closing a read-only channel; nothing actionable
            }
            archive = null;
            token = null;
        }
    }

    private void goHome() {
        if (archive == null) {
            return;
        }
        try {
            Optional<Dirent> main = archive.mainPage();
            if (main.isPresent()) {
                webView.getEngine().load(server.urlFor(token, main.get().fullPath()));
            } else {
                status.setText(bookTitle + " · this archive declares no main page — use search");
            }
        } catch (IOException e) {
            status.setText("Main page failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- search

    private void runSearch(String query) {
        if (archive == null || query == null || query.isBlank()) {
            results.getItems().clear();
            return;
        }
        long generation = searchGeneration.incrementAndGet();
        ZimArchive current = archive;
        int limit = settings.getSearchLimit();
        searchExecutor.execute(() -> {
            try {
                List<ZimArchive.SearchResult> found = current.searchByTitle(query.strip(), limit);
                if (generation == searchGeneration.get()) {
                    Platform.runLater(() -> {
                        if (generation == searchGeneration.get()) {
                            results.getItems().setAll(found);
                        }
                    });
                }
            } catch (IOException ignored) {
                // stale archive or truncated file; the next keystroke retries
            }
        });
    }

    private void openResult(ZimArchive.SearchResult result) {
        if (archive != null) {
            webView.getEngine().load(server.urlFor(token, result.fullPath()));
        }
    }

    // ---------------------------------------------------------------- navigation

    private void historyGo(int offset) {
        WebHistory history = webView.getEngine().getHistory();
        int target = history.getCurrentIndex() + offset;
        if (target >= 0 && target < history.getEntries().size()) {
            history.go(offset);
        }
    }

    private void updateNavButtons() {
        WebHistory history = webView.getEngine().getHistory();
        backButton.setDisable(history.getCurrentIndex() <= 0);
        forwardButton.setDisable(
                history.getCurrentIndex() >= history.getEntries().size() - 1);
    }

    private void onLocationChanged(String location) {
        if (location == null || server == null) {
            return;
        }
        boolean external = (location.startsWith("http://") || location.startsWith("https://"))
                && !location.startsWith(server.baseUrl());
        if (external) {
            // ZIM content is offline; anything pointing at the live web opens in the system browser.
            Platform.runLater(() -> {
                webView.getEngine().getLoadWorker().cancel();
                hostServices.showDocument(location);
            });
        } else if (location.startsWith(server.baseUrl())) {
            status.setText(bookTitle + " · "
                    + java.net.URLDecoder.decode(
                            location.substring(location.indexOf("/zim/") + 5),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    void dispose() {
        searchExecutor.shutdownNow();
        libraryPane.deactivate();
        downloads.close();
        catalog.close();
        closeArchive();
        if (server != null) {
            server.close();
        }
    }
}
