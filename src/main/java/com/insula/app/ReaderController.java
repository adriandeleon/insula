package com.insula.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.insula.catalog.CatalogCache;
import com.insula.catalog.StoreFilter;
import com.insula.catalog.ZimEntry;
import com.insula.command.CommandRegistry;
import com.insula.command.Keybindings;
import com.insula.config.Settings;
import com.insula.download.DownloadManager;
import com.insula.download.HttpMultiSourceTransport;
import com.insula.download.TorrentTransport;
import com.insula.download.TransportSelector;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;
import com.insula.reader.ReaderTheme;
import com.insula.reader.ReadingPositions;
import com.insula.reader.WebViewRenderer;
import com.insula.search.LibrarySearch;
import com.insula.server.ZimHttpServer;
import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;

/**
 * The reader shell: toolbar, a search-as-you-type sidebar over the archive's title index, and a
 * WebView reading pane fed by the loopback {@link ZimHttpServer}.
 *
 * <p>Every user-facing action is a {@link com.insula.command.Command} in the registry — the
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
    private final ListView<LibrarySearch.Result> results = new ListView<>();
    private final WebViewRenderer renderer = new WebViewRenderer();
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
    private Path currentArchiveFile;
    private String token;
    private String bookTitle = "";

    private final CatalogCache catalogCache;
    private final IconCache iconCache;
    private final StorePane storePane;
    private final Library library;
    private final TransportSelector transports;
    private final DownloadManager downloads;
    private final LibraryPane libraryPane;
    /** Cross-archive search: every archive in the library, not just the one being read. */
    private final LibrarySearch librarySearch = new LibrarySearch();

    private final java.util.Map<Path, ZimArchive> searchArchives = new java.util.LinkedHashMap<>();
    private final ReadingPositions positions;
    /** The article currently shown, for saving its scroll position when we navigate away. */
    private String currentPositionKey;

    private SplitPane readerSplit;
    private boolean showingLibrary;

    ReaderController(Stage stage, HostServices hostServices, Settings settings, Path dataDir) {
        this.stage = stage;
        this.hostServices = hostServices;
        this.settings = settings;
        this.library = Library.load(dataDir.resolve("library.properties"));
        this.positions = ReadingPositions.load(dataDir.resolve("reading.properties"));
        this.transports = new TransportSelector(new HttpMultiSourceTransport());
        // BitTorrent is an option, never the only way: it is registered only when its native
        // library actually loads, and HTTP stays the fallback regardless.
        if (TorrentTransport.isAvailable()) {
            transports.register(new TorrentTransport(settings.isSeedingEnabled()));
        }
        this.downloads = new DownloadManager(transports, library, dataDir.resolve("archives"));
        this.catalogCache = new CatalogCache(dataDir.resolve("catalog"));
        this.iconCache = new IconCache(dataDir.resolve("catalog/icons"));
        this.storePane = new StorePane(
                catalogCache,
                iconCache,
                (entry, title) -> {
                    downloads.enqueue(entry);
                    status.setText("Downloading " + title);
                },
                entry -> installedFileFor(entry) != null ? StorePane.Installed.YES : StorePane.Installed.NO,
                this::installedFileFor,
                this::openFromLibrary,
                status::setText,
                () -> freeDiskBytes(dataDir));
        this.libraryPane =
                new LibraryPane(storePane.node(), downloads, library, this::openFromLibrary, status::setText);
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

    WebViewRenderer rendererForTest() {
        return renderer;
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

    StorePane storePaneForTest() {
        return storePane;
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
        commands.register("reader.cycleMode", "Reader Mode: Original / Comfortable / Dark", this::cycleReaderMode);
        commands.register(
                "reader.modeOriginal", "Reader Mode: Original", () -> setReaderMode(ReaderTheme.Mode.ORIGINAL));
        commands.register(
                "reader.modeComfortable",
                "Reader Mode: Comfortable",
                () -> setReaderMode(ReaderTheme.Mode.COMFORTABLE));
        commands.register("reader.modeDark", "Reader Mode: Dark", () -> setReaderMode(ReaderTheme.Mode.DARK));
        commands.register("reader.widerColumn", "Reader: Wider Column", () -> adjustReaderWidth(100));
        commands.register("reader.narrowerColumn", "Reader: Narrower Column", () -> adjustReaderWidth(-100));
        commands.register("reader.toggleRememberPosition", "Toggle: Remember Reading Position", () -> {
            settings.setRememberPosition(!settings.isRememberPosition());
            saveAndApply();
            status.setText("Remember reading position: " + (settings.isRememberPosition() ? "on" : "off"));
        });
        commands.register("library.show", "Show Library and Downloads", this::showLibrary);
        commands.register("library.reader", "Back to Reader", this::showReader);
        commands.register("library.toggle", "Toggle Library / Reader", this::toggleLibrary);
        commands.register("library.search", "Search the Online Catalog", () -> {
            showLibrary();
            storePane.focusSearch();
        });
        commands.register("catalog.refresh", "Refresh the Catalog", () -> {
            showLibrary();
            storePane.refreshCommand();
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
        keys.bind("reader.cycleMode", new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN));
        keys.bind("catalog.refresh", new KeyCodeCombination(KeyCode.F5));
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
            protected void updateItem(LibrarySearch.Result item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title());
                Label source = new Label(item.archiveTitle());
                source.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.85em;");
                VBox box = new VBox(1, title, source);
                setGraphic(box);
            }
        });
        results.setOnMouseClicked(e -> {
            LibrarySearch.Result selected = results.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openResult(selected);
            }
        });
        results.setOnKeyPressed(e -> {
            LibrarySearch.Result selected = results.getSelectionModel().getSelectedItem();
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

        readerSplit = new SplitPane(sidebar, renderer.node());
        readerSplit.setOrientation(Orientation.HORIZONTAL);
        readerSplit.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);

        renderer.setOnLocationChanged(location -> {
            onLocationChanged(location);
            updateNavButtons();
        });

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
            storePane.activate();
            showingLibrary = true;
        }
    }

    /** The verified installed file matching this catalog entry's (name, flavour), or null. */
    private Path installedFileFor(ZimEntry entry) {
        String base = StoreFilter.installedBaseOf(entry.fileName());
        return library.verifiedEntries().stream()
                .filter(e -> StoreFilter.installedBaseOf(e.fileName()).equals(base))
                .map(com.insula.library.LibraryEntry::file)
                .findFirst()
                .orElse(null);
    }

    private static long freeDiskBytes(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            return Files.getFileStore(dataDir).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE; // unknown: do not artificially shrink the default flavour
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
        renderer.setZoom(settings.getZoomPercent() / 100.0);
        applyReaderTheme();
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
            stage.setTitle(bookTitle + " — Insula");
            status.setText(bookTitle + " · " + archive.entryCount() + " entries · " + file);
            homeButton.setDisable(false);
            results.getItems().clear();
            searchField.clear();
            currentArchiveFile = file.toAbsolutePath();
            settings.setLastArchive(currentArchiveFile.toString());
            settings.save();
            // The archive being read participates in search alongside the rest of the library.
            librarySearch.add(currentArchiveFile, bookTitle, archive);
            refreshSearchSources();
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
                renderer.load(server.urlFor(token, main.get().fullPath()));
            } else {
                status.setText(bookTitle + " · this archive declares no main page — use search");
            }
        } catch (IOException e) {
            status.setText("Main page failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- search

    private void runSearch(String query) {
        if (query == null || query.isBlank()) {
            results.getItems().clear();
            return;
        }
        long generation = searchGeneration.incrementAndGet();
        librarySearch.search(query, settings.getSearchLimit(), found -> {
            if (generation == searchGeneration.get()) {
                Platform.runLater(() -> {
                    if (generation == searchGeneration.get()) {
                        results.getItems().setAll(found);
                    }
                });
            }
        });
    }

    /**
     * Registers every verified archive in the library plus the one being read, so one search box
     * spans them all. Archives are opened here but indexed lazily on first search, so adding a
     * large library costs a file handle each and nothing more until the user actually types.
     */
    private void refreshSearchSources() {
        for (LibraryEntry entry : library.verifiedEntries()) {
            searchArchives.computeIfAbsent(entry.file(), file -> {
                try {
                    ZimArchive opened = ZimArchive.open(file);
                    librarySearch.add(file, entry.title(), opened);
                    return opened;
                } catch (IOException e) {
                    return null;
                }
            });
        }
        searchArchives.values().removeIf(java.util.Objects::isNull);
    }

    /**
     * Opens a result, switching archives first when the hit came from one that is not currently
     * being read — that is the whole point of searching across the library rather than within one
     * book.
     */
    private void openResult(LibrarySearch.Result result) {
        if (archive != null && result.archiveFile().equals(currentArchiveFile)) {
            renderer.load(server.urlFor(token, result.fullPath()));
            return;
        }
        openZim(result.archiveFile());
        if (archive != null) {
            renderer.load(server.urlFor(token, result.fullPath()));
        }
    }

    // ---------------------------------------------------------------- navigation

    private void historyGo(int offset) {
        if (offset < 0) {
            renderer.goBack();
        } else {
            renderer.goForward();
        }
        updateNavButtons();
    }

    private void updateNavButtons() {
        backButton.setDisable(!renderer.canGoBack());
        forwardButton.setDisable(!renderer.canGoForward());
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
                renderer.load("about:blank");
                hostServices.showDocument(location);
            });
        } else if (location.startsWith(server.baseUrl())) {
            String decoded = java.net.URLDecoder.decode(
                    location.substring(location.indexOf("/zim/") + 5), java.nio.charset.StandardCharsets.UTF_8);
            status.setText(bookTitle + " · " + decoded);
            onArticleShown(decoded);
        }
    }

    private void setReaderMode(ReaderTheme.Mode mode) {
        settings.setReaderMode(ReaderTheme.nameOf(mode));
        saveAndApply();
        status.setText("Reader mode: " + ReaderTheme.nameOf(mode));
    }

    private void cycleReaderMode() {
        ReaderTheme.Mode[] modes = ReaderTheme.Mode.values();
        ReaderTheme.Mode current = ReaderTheme.modeOf(settings.getReaderMode());
        setReaderMode(modes[(current.ordinal() + 1) % modes.length]);
    }

    private void adjustReaderWidth(int delta) {
        int width = ReaderTheme.clampWidth(settings.getReaderWidth() + delta);
        settings.setReaderWidth(width);
        saveAndApply();
        status.setText(
                width >= ReaderTheme.MAX_WIDTH ? "Reader column: unconstrained" : "Reader column: " + width + " px");
    }

    // ---------------------------------------------------------------- reading layer

    /** Re-applies the reader stylesheet over whatever the archive shipped. */
    private void applyReaderTheme() {
        renderer.injectCss(
                ReaderTheme.css(ReaderTheme.modeOf(settings.getReaderMode()), settings.getReaderWidth(), 1.0));
    }

    /**
     * Runs for every article the view lands on: saves where we were in the previous one, and
     * returns to the new one's remembered position.
     *
     * <p>The reader stylesheet is deliberately <b>not</b> re-applied here. It is a user stylesheet
     * on the engine, so it already covers every document loaded; re-applying it per navigation is
     * what made each link click flash, because the script landed after the new page had painted.
     */
    private void onArticleShown(String decodedPath) {
        savePosition();

        if (currentArchiveFile == null) {
            return;
        }
        int slash = decodedPath.indexOf('/');
        currentPositionKey =
                ReadingPositions.key(currentArchiveFile, slash < 0 ? decodedPath : decodedPath.substring(slash + 1));

        if (settings.isRememberPosition()) {
            double saved = positions.positionOf(currentPositionKey);
            if (saved > 0) {
                // The document has only just loaded; let layout settle first, or the fraction is
                // applied against a height that is still growing and lands in the wrong place.
                PauseTransition settle = new PauseTransition(Duration.millis(150));
                settle.setOnFinished(e -> renderer.scrollTo(saved));
                settle.play();
            }
        }
    }

    /** Records the scroll position of the article being left. */
    private void savePosition() {
        if (currentPositionKey != null && settings.isRememberPosition()) {
            positions.remember(currentPositionKey, renderer.scrollPosition());
        }
    }

    void dispose() {
        savePosition();
        try {
            positions.save();
        } catch (RuntimeException e) {
            // a failed position save must never block shutdown
        }
        renderer.dispose();
        searchExecutor.shutdownNow();
        librarySearch.close();
        searchArchives.values().forEach(a -> {
            try {
                a.close();
            } catch (IOException ignored) {
                // read-only handles; nothing actionable
            }
        });
        searchArchives.clear();
        libraryPane.deactivate();
        downloads.close();
        catalogCache.close();
        iconCache.close();
        closeArchive();
        if (server != null) {
            server.close();
        }
    }
}
