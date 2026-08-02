package com.insula.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import javafx.geometry.Pos;
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
import com.insula.catalog.StarterPicks;
import com.insula.catalog.StoreFilter;
import com.insula.catalog.UpdateCheck;
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
import com.insula.media.HlsSession;
import com.insula.media.TranscodeService;
import com.insula.reader.ArticleLocation;
import com.insula.reader.ArticleRef;
import com.insula.reader.ArticleStore;
import com.insula.reader.MediaBridge;
import com.insula.reader.MediaFallback;
import com.insula.reader.ReaderTabs;
import com.insula.reader.ReaderTheme;
import com.insula.reader.ReaderView;
import com.insula.reader.ReaderViewSession;
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
    private final Button readerViewButton = new Button("Reader");
    private final Button typePanelButton = new Button("Aa");
    private javafx.stage.Popup typePanel;
    private ReaderViewSession readerView;
    private final javafx.scene.control.ToggleButton libraryTab = new javafx.scene.control.ToggleButton("Library");
    private final javafx.scene.control.ToggleButton storeTab = new javafx.scene.control.ToggleButton("Store");
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
    private String lastArticlePath;

    private final ReaderTabs tabs = new ReaderTabs();
    private final javafx.scene.control.TabPane tabBar = new javafx.scene.control.TabPane();
    private final java.util.Map<javafx.scene.control.Tab, ReaderTabs.Tab> tabModel = new java.util.IdentityHashMap<>();
    private boolean syncingTabs;
    private ArticleStore bookmarks;
    private ArticleStore history;
    private final ListView<ArticleRef> bookmarkList = new ListView<>();
    private final ListView<ArticleRef> historyList = new ListView<>();
    private final Button bookmarkButton = new Button("☆");
    private String token;
    private String bookTitle = "";

    private static final int MAX_BOOKMARKS = 500;
    private static final int MAX_HISTORY = 300;

    private final CatalogCache catalogCache;
    private final TranscodeService transcodes;
    private boolean transcodeAvailable;
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
    private VBox[] sidebarPanes;
    private VBox sidebarHost;

    /** Which surface fills the window: the Reader, the Library (home), or the Store. */
    enum Surface {
        READER,
        LIBRARY,
        STORE
    }

    private Surface surface = Surface.READER;

    private final Path dataDir;

    ReaderController(Stage stage, HostServices hostServices, Settings settings, Path dataDir) {
        this.dataDir = dataDir;
        this.stage = stage;
        this.hostServices = hostServices;
        this.settings = settings;
        this.library = Library.load(dataDir.resolve("library.properties"));
        this.positions = ReadingPositions.load(dataDir.resolve("reading.properties"));
        this.bookmarks = ArticleStore.load(dataDir.resolve("bookmarks.properties"), MAX_BOOKMARKS);
        this.history = ArticleStore.load(dataDir.resolve("history.properties"), MAX_HISTORY);
        this.transports = new TransportSelector(new HttpMultiSourceTransport());
        // BitTorrent is an option, never the only way: it is registered only when its native
        // library actually loads, and HTTP stays the fallback regardless.
        if (torrentTransportPresent()) {
            transports.register(new TorrentTransport(settings.isSeedingEnabled()));
        }
        this.downloads = new DownloadManager(transports, library, dataDir.resolve("archives"));
        downloads.setOnAdmitted(job -> Platform.runLater(() -> afterArchiveAdmitted(job)));
        this.transcodes = new TranscodeService();
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
                new LibraryPane(downloads, library, this::openFromLibrary, status::setText, () -> diskInfo(dataDir));
        buildUi();
        registerCommands();
        bindKeys();
        palette = new CommandPalette(root, commands, keys::displayFor);
        applySettings();
        // Verification a previous run never finished resumes now that the whole shell exists.
        downloads.resumePendingVerification(msg -> Platform.runLater(() -> {
            status.setText(msg);
            if (surface == Surface.LIBRARY) {
                refreshAttention();
                libraryPane.activate();
            }
        }));
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

    com.insula.library.Library libraryForTest() {
        return library;
    }

    LibraryPane libraryPaneForTest() {
        return libraryPane;
    }

    boolean libraryShowingForTest() {
        return surface != Surface.READER;
    }

    Surface surfaceForTest() {
        return surface;
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
        commands.register("readerview.toggle", "Toggle Reader View", this::toggleReaderView);
        commands.register("tab.new", "New Tab", this::newTab);
        commands.register("tab.close", "Close Tab", this::closeActiveTab);
        commands.register("tab.next", "Next Tab", () -> cycleTab(1));
        commands.register("tab.previous", "Previous Tab", () -> cycleTab(-1));
        commands.register("bookmark.toggle", "Bookmark This Article", this::toggleBookmark);
        commands.register("bookmark.show", "Show Bookmarks", () -> {
            showReader();
            showSidebarPane(1);
        });
        commands.register("history.show", "Show History", () -> {
            showReader();
            showSidebarPane(2);
        });
        commands.register("history.clear", "Clear History", this::clearHistory);
        commands.register("search.show", "Show Search", () -> showSidebarPane(0));
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
        commands.register("library.open", "Show the Library", this::showLibrary);
        commands.register("store.open", "Show the Store", this::showStore);
        commands.register("library.show", "Show Library and Downloads", this::showLibrary);
        commands.register("library.reader", "Back to Reader", this::showReader);
        commands.register("library.toggle", "Toggle Library / Reader", this::toggleLibrary);
        commands.register("library.checkUpdates", "Check for Archive Updates", () -> checkForUpdates(true));
        commands.register("library.updateAll", "Update All Archives", this::updateAllArchives);
        commands.register("library.repairAll", "Repair Quarantined Archives", this::repairAllQuarantined);
        commands.register("lan.share", "Share Library on Local Network", this::toggleLanSharing);
        commands.register("library.search", "Search the Online Catalog", () -> {
            showStore();
            storePane.focusSearch();
        });
        commands.register("catalog.refresh", "Refresh the Catalog", () -> {
            showStore();
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
        keys.bind("library.open", new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN));
        keys.bind("store.open", new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN));
        keys.bind("reader.cycleMode", new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN));
        keys.bind(
                "readerview.toggle",
                new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        keys.bind("catalog.refresh", new KeyCodeCombination(KeyCode.F5));
    }

    void installShortcuts(Scene scene) {
        keys.install(scene, commands);
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        readerView = new ReaderViewSession(renderer::runScript);
        Button openButton = new Button("Open…");
        openButton.setOnAction(e -> commands.run("file.open"));
        backButton.setOnAction(e -> commands.run("nav.back"));
        forwardButton.setOnAction(e -> commands.run("nav.forward"));
        homeButton.setOnAction(e -> commands.run("nav.home"));
        backButton.setDisable(true);
        forwardButton.setDisable(true);
        homeButton.setDisable(true);

        bookmarkButton.setOnAction(e -> commands.run("bookmark.toggle"));
        bookmarkButton.setTooltip(new javafx.scene.control.Tooltip("Bookmark this article (Ctrl+D)"));
        bookmarkButton.setDisable(true);
        readerViewButton.setOnAction(e -> commands.run("readerview.toggle"));
        readerViewButton.setDisable(true);
        readerViewButton.setTooltip(new javafx.scene.control.Tooltip("Reader View (Ctrl+Alt+R)"));
        typePanelButton.setOnAction(e -> toggleTypePanel());
        typePanelButton.setTooltip(new javafx.scene.control.Tooltip("Type and layout"));
        typePanelButton.setVisible(false);
        typePanelButton.setManaged(false);

        libraryTab.setOnAction(e -> commands.run("library.open"));
        storeTab.setOnAction(e -> commands.run("store.open"));
        HBox navTabs = new HBox(2, libraryTab, storeTab);
        navTabs.setAlignment(Pos.CENTER_LEFT);
        Button paletteButton = new Button("⌘K");
        paletteButton.setTooltip(new javafx.scene.control.Tooltip("Command palette"));
        paletteButton.setOnAction(e -> commands.run("view.commandPalette"));
        Button settingsButton = new Button("Settings");
        settingsButton.setOnAction(e -> commands.run("view.settings"));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolbar = new ToolBar(
                openButton,
                navTabs,
                new Separator(),
                backButton,
                forwardButton,
                homeButton,
                new Separator(),
                readerViewButton,
                typePanelButton,
                bookmarkButton,
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

        VBox searchPane = new VBox(8, searchField, results);
        VBox.setVgrow(results, Priority.ALWAYS);
        VBox sidebar = new VBox(8, buildSidebarSwitcher(), searchPane);
        VBox.setVgrow(searchPane, Priority.ALWAYS);
        sidebar.setPadding(new Insets(8));
        sidebar.setMinWidth(220);
        sidebar.setPrefWidth(300);
        sidebarPanes = new VBox[] {searchPane, wrap(bookmarkList), wrap(historyList)};
        sidebarHost = sidebar;
        for (int i = 1; i < sidebarPanes.length; i++) {
            sidebarPanes[i].setVisible(false);
            sidebarPanes[i].setManaged(false);
            sidebar.getChildren().add(sidebarPanes[i]);
            VBox.setVgrow(sidebarPanes[i], Priority.ALWAYS);
        }
        buildArticleLists();

        // The tab strip sits directly over the article; the renderer is moved into whichever tab
        // is showing, because all tabs share one engine.
        tabBar.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.ALL_TABS);
        tabBar.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (!syncingTabs && selected != null) {
                onTabSelected(selected);
            }
        });
        tabBar.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) change -> {
            while (change.next()) {
                for (javafx.scene.control.Tab removed : change.getRemoved()) {
                    onTabClosedByUser(removed);
                }
            }
        });

        readerSplit = new SplitPane(sidebar, tabBar);
        readerSplit.setOrientation(Orientation.HORIZONTAL);
        readerSplit.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);

        renderer.setOnLocationChanged(location -> {
            onLocationChanged(location);
            updateNavButtons();
        });
        // Both of these need a parsed document; locationProperty fires before that exists.
        renderer.engine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                probeReaderable();
                installMediaFallback();
            }
        });

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(4, 10, 4, 10));

        shell.setTop(toolbar);
        shell.setCenter(readerSplit);
        shell.setBottom(statusBar);
        root.getChildren().add(shell);
    }

    // ---------------------------------------------------------------- tabs

    private VBox wrap(ListView<ArticleRef> list) {
        VBox box = new VBox(list);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    private Region buildSidebarSwitcher() {
        javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        HBox row = new HBox(2);
        String[] names = {"Search", "Bookmarks", "History"};
        for (int i = 0; i < names.length; i++) {
            javafx.scene.control.ToggleButton button = new javafx.scene.control.ToggleButton(names[i]);
            button.setToggleGroup(group);
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            int index = i;
            button.setSelected(i == 0);
            button.setOnAction(e -> showSidebarPane(index));
            row.getChildren().add(button);
            sidebarButtons.add(button);
        }
        return row;
    }

    private final List<javafx.scene.control.ToggleButton> sidebarButtons = new java.util.ArrayList<>();

    /** 0 = search, 1 = bookmarks, 2 = history. */
    private void showSidebarPane(int index) {
        if (sidebarPanes == null) {
            return;
        }
        for (int i = 0; i < sidebarPanes.length; i++) {
            sidebarPanes[i].setVisible(i == index);
            sidebarPanes[i].setManaged(i == index);
        }
        if (index < sidebarButtons.size()) {
            sidebarButtons.get(index).setSelected(true);
        }
        if (index == 1) {
            refreshBookmarkList();
        } else if (index == 2) {
            refreshHistoryList();
        }
    }

    int sidebarPaneForTest() {
        for (int i = 0; sidebarPanes != null && i < sidebarPanes.length; i++) {
            if (sidebarPanes[i].isVisible()) {
                return i;
            }
        }
        return -1;
    }

    private void buildArticleLists() {
        for (ListView<ArticleRef> list : List.of(bookmarkList, historyList)) {
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(ArticleRef item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                        return;
                    }
                    Label title = new Label(item.title());
                    Label source = new Label(item.bookTitle());
                    source.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.85em;");
                    setGraphic(new VBox(1, title, source));
                }
            });
            list.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    openRef(list.getSelectionModel().getSelectedItem(), false);
                }
            });
            list.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    openRef(list.getSelectionModel().getSelectedItem(), false);
                } else if (e.getCode() == KeyCode.DELETE && list == bookmarkList) {
                    removeBookmark(list.getSelectionModel().getSelectedItem());
                }
            });
        }
    }

    private void refreshBookmarkList() {
        bookmarkList.getItems().setAll(bookmarks.entries());
    }

    private void refreshHistoryList() {
        historyList.getItems().setAll(history.entries());
    }

    /** The article currently showing, or null when nothing is open. */
    private ArticleRef currentRef() {
        if (archive == null || currentArchiveFile == null || lastArticlePath == null) {
            return null;
        }
        int slash = lastArticlePath.indexOf('/');
        String path = slash < 0 ? lastArticlePath : lastArticlePath.substring(slash + 1);
        return new ArticleRef(currentArchiveFile, path, articleTitle(path), bookTitle);
    }

    /** The archive's own title for an entry, which is better than the path and costs one lookup. */
    private String articleTitle(String path) {
        try {
            var entry = archive.entryByUrl(path);
            if (entry.isPresent()) {
                String title = archive.resolve(entry.get()).title();
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the path, which is always usable
        }
        return path;
    }

    /** Opens a stored reference, switching archives when it belongs to another book. */
    private void openRef(ArticleRef ref, boolean inNewTab) {
        if (ref == null) {
            return;
        }
        if (!java.nio.file.Files.isRegularFile(ref.archiveFile())) {
            status.setText("That archive is no longer on this device: "
                    + ref.archiveFile().getFileName());
            return;
        }
        showReader();
        if (inNewTab) {
            openTabFor(ref);
            return;
        }
        if (archive == null || !ref.archiveFile().equals(currentArchiveFile)) {
            openZim(ref.archiveFile());
        }
        if (archive != null) {
            renderer.load(server.urlFor(token, ref.articlePath()));
        }
    }

    // ---- tab plumbing: the model decides, the strip follows ----

    private void openTabFor(ArticleRef ref) {
        ReaderTabs.Tab tab = tabs.open(ref, true);
        syncTabBar();
        activateTab(tab);
    }

    /**
     * A new tab starts at the archive's main page, not at a copy of the current article — with no
     * address bar, the front of the book is the only meaningful fresh start.
     */
    private void newTab() {
        ArticleRef ref = mainPageRef();
        if (ref == null) {
            showLibrary(); // nothing to put in a tab yet; the library is where one gets filled
            return;
        }
        openTabFor(ref);
    }

    private ArticleRef mainPageRef() {
        if (archive == null || currentArchiveFile == null) {
            return null;
        }
        try {
            Optional<Dirent> main = archive.mainPage();
            if (main.isPresent()) {
                String path = main.get().fullPath();
                return new ArticleRef(currentArchiveFile, path, articleTitle(path), bookTitle);
            }
        } catch (IOException e) {
            // fall through: an archive with no readable main page still gets a tab below
        }
        return currentRef();
    }

    private void closeActiveTab() {
        if (tabs.count() == 0) {
            return;
        }
        ReaderTabs.Tab next = tabs.closeActive();
        syncTabBar();
        if (next == null) {
            showLibrary();
        } else {
            activateTab(next);
        }
    }

    private void cycleTab(int delta) {
        ReaderTabs.Tab tab = delta > 0 ? tabs.next() : tabs.previous();
        if (tab != null) {
            syncTabBar();
            activateTab(tab);
        }
    }

    /** Rebuilds the strip from the model. The guard stops the rebuild firing selection events. */
    private void syncTabBar() {
        syncingTabs = true;
        try {
            tabModel.clear();
            tabBar.getTabs().clear();
            for (ReaderTabs.Tab tab : tabs.tabs()) {
                javafx.scene.control.Tab uiTab = new javafx.scene.control.Tab(tab.label());
                tabModel.put(uiTab, tab);
                tabBar.getTabs().add(uiTab);
            }
            if (tabs.activeIndex() >= 0 && tabs.activeIndex() < tabBar.getTabs().size()) {
                javafx.scene.control.Tab selected = tabBar.getTabs().get(tabs.activeIndex());
                selected.setContent(renderer.node());
                tabBar.getSelectionModel().select(selected);
            }
        } finally {
            syncingTabs = false;
        }
    }

    private void onTabSelected(javafx.scene.control.Tab uiTab) {
        ReaderTabs.Tab tab = tabModel.get(uiTab);
        if (tab == null || tab == tabs.active()) {
            return;
        }
        rememberScrollForActiveTab();
        tabs.selectTab(tab);
        moveRendererInto(uiTab);
        activateTab(tab);
    }

    private void onTabClosedByUser(javafx.scene.control.Tab uiTab) {
        if (syncingTabs) {
            return;
        }
        ReaderTabs.Tab tab = tabModel.remove(uiTab);
        if (tab == null) {
            return;
        }
        int index = tabs.tabs().indexOf(tab);
        ReaderTabs.Tab next = tabs.close(index);
        syncTabBar();
        if (next == null) {
            showLibrary();
        } else {
            activateTab(next);
        }
    }

    /** All tabs share one engine, so the renderer node moves to whichever tab is showing. */
    private void moveRendererInto(javafx.scene.control.Tab uiTab) {
        for (javafx.scene.control.Tab other : tabBar.getTabs()) {
            if (other != uiTab && other.getContent() == renderer.node()) {
                other.setContent(null);
            }
        }
        uiTab.setContent(renderer.node());
    }

    /** Loads the tab's article and restores the scroll it recorded. */
    private void activateTab(ReaderTabs.Tab tab) {
        if (tab == null || tab.article() == null) {
            return;
        }
        ArticleRef ref = tab.article();
        if (archive == null || !ref.archiveFile().equals(currentArchiveFile)) {
            openZim(ref.archiveFile());
        }
        if (archive == null) {
            return;
        }
        renderer.load(server.urlFor(token, ref.articlePath()));
        double scroll = tab.scroll();
        if (scroll > 0) {
            PauseTransition settle = new PauseTransition(Duration.millis(200));
            settle.setOnFinished(e -> renderer.scrollTo(scroll));
            settle.play();
        }
    }

    /**
     * Following a link inside a tab changes what that tab holds; without this the strip would keep
     * showing the title the tab was opened with and a switch back would reload the wrong article.
     */
    private void syncActiveTabToCurrentArticle() {
        ArticleRef ref = currentRef();
        if (ref == null) {
            return;
        }
        ReaderTabs.Tab active = tabs.active();
        if (active == null) {
            active = tabs.open(ref, true);
            syncTabBar();
            return;
        }
        active.setArticle(ref);
        active.setScroll(0);
        int index = tabs.activeIndex();
        if (index >= 0 && index < tabBar.getTabs().size()) {
            tabBar.getTabs().get(index).setText(ref.title());
        }
    }

    private void rememberScrollForActiveTab() {
        ReaderTabs.Tab active = tabs.active();
        if (active != null && surface == Surface.READER) {
            active.setScroll(renderer.scrollPosition());
        }
    }

    ReaderTabs tabsForTest() {
        return tabs;
    }

    // ---------------------------------------------------------------- bookmarks and history

    ArticleStore bookmarksForTest() {
        return bookmarks;
    }

    ArticleStore historyForTest() {
        return history;
    }

    private void toggleBookmark() {
        ArticleRef ref = currentRef();
        if (ref == null) {
            status.setText("Open an article first");
            return;
        }
        if (bookmarks.contains(ref)) {
            bookmarks.remove(ref);
            status.setText("Removed bookmark");
        } else {
            bookmarks.addLast(ref);
            status.setText("Bookmarked " + ref.title());
        }
        bookmarks.save();
        refreshBookmarkList();
        updateBookmarkButton();
    }

    private void removeBookmark(ArticleRef ref) {
        if (ref != null && bookmarks.remove(ref)) {
            bookmarks.save();
            refreshBookmarkList();
            updateBookmarkButton();
            status.setText("Removed bookmark");
        }
    }

    /** A filled star means "this article is saved" — the one piece of state the toolbar shows. */
    private void updateBookmarkButton() {
        ArticleRef ref = currentRef();
        boolean saved = ref != null && bookmarks.contains(ref);
        bookmarkButton.setText(saved ? "★" : "☆");
        bookmarkButton.setDisable(ref == null);
    }

    private void recordHistory() {
        ArticleRef ref = currentRef();
        if (ref != null) {
            history.addFirst(ref);
            if (sidebarPaneForTest() == 2) {
                refreshHistoryList();
            }
        }
    }

    private void clearHistory() {
        history.clear();
        history.save();
        refreshHistoryList();
        status.setText("History cleared");
    }

    // ---------------------------------------------------------------- library / downloads

    private void showLibrary() {
        if (surface != Surface.LIBRARY) {
            if (surface == Surface.STORE) {
                // leaving the store costs nothing; its state is plain fields
            }
            shell.setCenter(libraryPane.node());
            libraryPane.activate();
            surface = Surface.LIBRARY;
            syncNavTabs();
            refreshAttention();
            checkForUpdates(false); // starters + update pills, off-thread, disk cache only
        }
    }

    private void showStore() {
        if (surface != Surface.STORE) {
            libraryPane.deactivate();
            shell.setCenter(storePane.node());
            storePane.activate();
            surface = Surface.STORE;
            syncNavTabs();
        }
    }

    private void showReader() {
        if (surface != Surface.READER) {
            libraryPane.deactivate();
            shell.setCenter(readerSplit);
            surface = Surface.READER;
            syncNavTabs();
        }
    }

    private void toggleLibrary() {
        if (surface == Surface.READER) {
            showLibrary();
        } else {
            showReader();
        }
    }

    /** Lands on the Library at startup when nothing is being read — the spec's "Library is home". */
    void landOnLibraryIfIdle() {
        if (archive == null) {
            showLibrary();
        }
    }

    private void syncNavTabs() {
        libraryTab.setSelected(surface == Surface.LIBRARY);
        storeTab.setSelected(surface == Surface.STORE);
    }

    // ---------------------------------------------------------------- archive updates

    private Map<String, ZimEntry> availableUpdates = Map.of();

    /** Test seam: replaces the modal superseded-file confirm. Args: file names, bytes freed. */
    private java.util.function.BiPredicate<List<String>, Long> supersededConfirmer = this::confirmSupersededDelete;

    void supersededConfirmerForTest(java.util.function.BiPredicate<List<String>, Long> confirmer) {
        this.supersededConfirmer = confirmer;
    }

    Map<String, ZimEntry> availableUpdatesForTest() {
        return availableUpdates;
    }

    /** The async worker's exact body, run synchronously on the caller: updates + starters. */
    void checkForUpdatesSyncForTest() {
        if (catalogCache.entries().isEmpty()) {
            catalogCache.load();
        }
        applyUpdates(UpdateCheck.findUpdates(installedFileNames(), catalogCache.entries()), false);
        List<StarterPicks.Resolved> starters = library.entries().isEmpty()
                ? StarterPicks.resolve(
                        com.insula.catalog.CatalogGroups.group(catalogCache.entries()), freeDiskBytes(dataDir))
                : List.of();
        libraryPane.setStarters(starters, this::downloadUpdate, this::showStore);
    }

    private List<String> installedFileNames() {
        return library.entries().stream()
                .map(com.insula.library.LibraryEntry::fileName)
                .toList();
    }

    /**
     * Computes updates off the FX thread — the catalog may need a 4 MB parse from disk first —
     * and lands the result as pills on the Library rows.
     */
    private void checkForUpdates(boolean announce) {
        if (announce) {
            status.setText("Checking for updates…");
        }
        List<String> installed = installedFileNames();
        long free = freeDiskBytes(dataDir);
        Thread checker = new Thread(
                () -> {
                    if (catalogCache.entries().isEmpty()) {
                        catalogCache.load();
                    }
                    List<UpdateCheck.Update> updates = UpdateCheck.findUpdates(installed, catalogCache.entries());
                    List<StarterPicks.Resolved> starters = installed.isEmpty()
                            ? StarterPicks.resolve(com.insula.catalog.CatalogGroups.group(catalogCache.entries()), free)
                            : List.of();
                    Platform.runLater(() -> {
                        applyUpdates(updates, announce);
                        libraryPane.setStarters(starters, this::downloadUpdate, this::showStore);
                    });
                },
                "update-check");
        checker.setDaemon(true);
        checker.start();
    }

    private void applyUpdates(List<UpdateCheck.Update> updates, boolean announce) {
        Map<String, ZimEntry> byFile = new java.util.LinkedHashMap<>();
        for (UpdateCheck.Update update : updates) {
            byFile.put(update.installedFileName(), update.replacement());
        }
        availableUpdates = byFile;
        libraryPane.setUpdates(byFile, this::downloadUpdate);
        if (announce) {
            status.setText(
                    updates.isEmpty()
                            ? "Everything is up to date"
                            : updates.size() + (updates.size() == 1 ? " update available" : " updates available"));
        }
    }

    private void downloadUpdate(ZimEntry replacement) {
        downloads.enqueue(replacement);
        status.setText("Downloading " + replacement.displayName());
        if (surface == Surface.LIBRARY) {
            libraryPane.activate(); // restart the sampler so the Arriving row appears now
        }
    }

    private void updateAllArchives() {
        if (availableUpdates.isEmpty()) {
            checkForUpdates(true);
            return;
        }
        availableUpdates.values().forEach(downloads::enqueue);
        status.setText(
                "Updating " + availableUpdates.size() + (availableUpdates.size() == 1 ? " archive" : " archives"));
        showLibrary();
        libraryPane.activate();
    }

    /**
     * A verified download just joined the library. If it supersedes older installed builds,
     * offer — never silently perform — their deletion; the file being read is never touched.
     */
    private void afterArchiveAdmitted(DownloadManager.Job job) {
        afterAdmittedFile(job.destination().getFileName().toString());
    }

    void afterAdmittedFile(String newName) {
        List<com.insula.library.LibraryEntry> superseded = library.entries().stream()
                .filter(e -> UpdateCheck.supersedes(newName, e.fileName()))
                .filter(e -> currentArchiveFile == null || !e.file().equals(currentArchiveFile))
                .toList();
        if (!superseded.isEmpty()) {
            List<String> names = superseded.stream()
                    .map(com.insula.library.LibraryEntry::fileName)
                    .toList();
            long bytes = superseded.stream()
                    .mapToLong(com.insula.library.LibraryEntry::sizeBytes)
                    .sum();
            if (supersededConfirmer.test(names, bytes)) {
                for (com.insula.library.LibraryEntry old : superseded) {
                    try {
                        Files.deleteIfExists(old.file());
                        library.remove(old.file());
                    } catch (IOException e) {
                        status.setText("Could not delete " + old.fileName() + ": " + e.getMessage());
                    }
                }
                library.save();
                status.setText("Removed " + names.size() + " superseded " + (names.size() == 1 ? "file" : "files")
                        + " · freed " + Formats.bytes(bytes));
            }
        }
        // Either way the world changed: refresh pills and rows against the new installed set.
        applyUpdates(UpdateCheck.findUpdates(installedFileNames(), catalogCache.entries()), false);
        if (surface == Surface.LIBRARY) {
            libraryPane.activate();
        }
    }

    private boolean confirmSupersededDelete(List<String> names, long bytes) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "The update replaces:\n" + String.join("\n", names) + "\n\nDelete "
                        + (names.size() == 1 ? "it" : "them") + " and free " + Formats.bytes(bytes) + "?",
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        alert.setHeaderText("Delete superseded archive" + (names.size() == 1 ? "" : "s") + "?");
        return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK;
    }

    // ---------------------------------------------------------------- LAN sharing

    private com.insula.server.LanServer lanServer;
    private final List<com.insula.zim.ZimArchive> lanArchives = new java.util.ArrayList<>();
    private Stage lanShareWindow;

    com.insula.server.LanServer lanServerForTest() {
        return lanServer;
    }

    private void toggleLanSharing() {
        if (lanServer != null) {
            stopLanSharing();
            return;
        }
        String url = startLanSharing();
        if (url != null) {
            showLanShareWindow(url);
        }
    }

    /**
     * Opens every verified library archive read-only and serves it on the LAN. Session-only by
     * design; returns the URL other devices should use, or null when nothing could be shared.
     */
    String startLanSharing() {
        List<com.insula.library.LibraryEntry> entries = library.verifiedEntries();
        if (entries.isEmpty()) {
            status.setText("Nothing to share — the library has no verified archives");
            return null;
        }
        try {
            lanServer = new com.insula.server.LanServer(
                    new java.net.InetSocketAddress(0).getAddress(), settings.getLanPort());
        } catch (IOException e) {
            status.setText("Could not start sharing: " + e.getMessage());
            lanServer = null;
            return null;
        }
        int sharedCount = 0;
        for (com.insula.library.LibraryEntry entry : entries) {
            try {
                com.insula.zim.ZimArchive opened = com.insula.zim.ZimArchive.open(entry.file());
                lanArchives.add(opened);
                lanServer.share(
                        com.insula.server.LanServer.slugFor(entry.fileName()),
                        entry.title(),
                        entry.sizeBytes(),
                        opened);
                sharedCount++;
            } catch (IOException e) {
                status.setText("Could not share " + entry.fileName() + ": " + e.getMessage());
            }
        }
        if (sharedCount == 0) {
            stopLanSharing();
            return null;
        }
        String host = com.insula.server.LanServer.lanAddress().orElse("127.0.0.1");
        String url = "http://" + host + ":" + lanServer.port() + "/";
        status.setText("Sharing " + sharedCount + (sharedCount == 1 ? " archive at " : " archives at ") + url);
        return url;
    }

    void stopLanSharing() {
        if (lanShareWindow != null) {
            lanShareWindow.close();
            lanShareWindow = null;
        }
        if (lanServer != null) {
            lanServer.close();
            lanServer = null;
        }
        for (com.insula.zim.ZimArchive opened : lanArchives) {
            try {
                opened.close();
            } catch (IOException ignored) {
                // read-only handles; nothing to lose
            }
        }
        lanArchives.clear();
        status.setText("Stopped sharing");
    }

    /** URL + QR in a small window — the point is a phone camera, not a copy-paste. */
    private void showLanShareWindow(String url) {
        javafx.scene.image.ImageView qr = new javafx.scene.image.ImageView(QrImage.render(url));
        Label heading = new Label("Scan on your phone, or open:");
        TextField urlField = new TextField(url);
        urlField.setEditable(false);
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(url);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status.setText("Copied " + url);
        });
        HBox urlRow = new HBox(8, urlField, copy);
        HBox.setHgrow(urlField, Priority.ALWAYS);
        Label note = new Label("Visible to everyone on this network. Sharing stops when you close Insula.");
        note.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.85em;");
        note.setWrapText(true);
        Button stop = new Button("Stop sharing");
        stop.setOnAction(e -> stopLanSharing());

        VBox box = new VBox(12, heading, qr, urlRow, note, stop);
        box.setPadding(new Insets(18));
        box.setAlignment(Pos.CENTER);
        lanShareWindow = new Stage();
        lanShareWindow.setTitle("Share on local network");
        lanShareWindow.setScene(new Scene(box));
        lanShareWindow.setOnCloseRequest(e -> lanShareWindow = null);
        lanShareWindow.show();
    }

    // ---------------------------------------------------------------- quarantine repair

    /** Test seam: replaces the modal delete-quarantined confirm. */
    private java.util.function.Predicate<Path> quarantineDeleteConfirmer = this::confirmQuarantineDelete;

    void quarantineDeleteConfirmerForTest(java.util.function.Predicate<Path> confirmer) {
        this.quarantineDeleteConfirmer = confirmer;
    }

    /** Quarantined downloads on disk, oldest name first. */
    List<Path> listQuarantined() {
        try (var stream = Files.list(dataDir.resolve("archives"))) {
            return stream.filter(f -> f.getFileName().toString().contains(com.insula.download.Quarantine.SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    void refreshAttention() {
        libraryPane.setAttention(listQuarantined(), this::repairArchive, this::deleteQuarantined);
    }

    private void repairArchive(Path quarantined) {
        status.setText("Repairing " + quarantined.getFileName() + "…");
        downloads.repairQuarantined(
                quarantined,
                msg -> Platform.runLater(() -> status.setText(msg)),
                ok -> Platform.runLater(() -> {
                    refreshAttention();
                    if (surface == Surface.LIBRARY) {
                        libraryPane.activate();
                    }
                }));
    }

    private void repairAllQuarantined() {
        List<Path> files = listQuarantined();
        if (files.isEmpty()) {
            status.setText("Nothing is quarantined");
            return;
        }
        showLibrary();
        files.forEach(this::repairArchive);
    }

    private void deleteQuarantined(Path file) {
        if (!quarantineDeleteConfirmer.test(file)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
            // Drop the sidecar only when no other copy still needs it.
            Path zim = com.insula.download.RecoverySidecar.zimFor(file);
            if (!Files.exists(zim) && listQuarantined().isEmpty()) {
                com.insula.download.RecoverySidecar.delete(zim);
            }
            status.setText("Deleted " + file.getFileName());
        } catch (IOException e) {
            status.setText("Could not delete " + file.getFileName() + ": " + e.getMessage());
        }
        refreshAttention();
    }

    private boolean confirmQuarantineDelete(Path file) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Delete " + file.getFileName() + "? Repair could still salvage it without a full re-download.",
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        alert.setHeaderText("Delete quarantined file?");
        return alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK;
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

    /** Everything the Library's disk gauge shows; free/total zero out on I/O failure rather than throwing. */
    private LibraryPane.DiskInfo diskInfo(Path dataDir) {
        long used = library.entries().stream()
                .mapToLong(com.insula.library.LibraryEntry::sizeBytes)
                .sum();
        long free = 0;
        long total = 0;
        try {
            Files.createDirectories(dataDir);
            var store = Files.getFileStore(dataDir);
            free = store.getUsableSpace();
            total = store.getTotalSpace();
        } catch (IOException ignored) {
            // gauge shows zeros rather than failing the pane
        }
        return new LibraryPane.DiskInfo(used, library.entries().size(), free, total);
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
        applyTranscodeSupport();
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
            lastArticlePath = null;
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
            String decoded = ArticleLocation.articlePath(location, server.baseUrl());
            if (decoded == null) {
                return;
            }
            status.setText(bookTitle + " · " + decoded);
            // A table-of-contents click is a fragment navigation: the engine reports it exactly
            // like a real one, but the document has not changed. Doing article-changed work here
            // would exit Reader View on a heading click and restore a stored scroll position over
            // the anchor the reader just asked for.
            if (decoded.equals(lastArticlePath)) {
                return;
            }
            lastArticlePath = decoded;
            resetReaderViewForNavigation();
            onArticleShown(decoded);
            recordHistory();
            updateBookmarkButton();
            syncActiveTabToCurrentArticle();
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

    // ---------------------------------------------------------------- unplayable media

    /**
     * Replaces media WebView cannot decode with a placeholder offering external playback.
     *
     * <p>Runs per document because the bridge binding and the DOM edit both belong to the page.
     * The engine decides what is unplayable, so an archive that ships MP4 is left untouched and
     * this costs one {@code querySelectorAll} on a page with no media at all.
     */
    private void installMediaFallback() {
        renderer.installBridge(MediaFallback.BRIDGE, new MediaBridge(this::playExternally, this::playInApp));
        boolean inApp = settings.isVideoTranscode() && transcodeAvailable;
        Object replaced = renderer.runScript(MediaFallback.installScript(
                inApp ? MediaFallback.transcodeLabel() : MediaFallback.defaultLabel(),
                "Open externally",
                inApp ? "▶  Play here" : null));
        if (replaced instanceof Number n && n.intValue() > 0) {
            lastUnplayableCount = n.intValue();
        } else {
            lastUnplayableCount = 0;
        }
    }

    private int lastUnplayableCount;

    int unplayableMediaCountForTest() {
        return lastUnplayableCount;
    }

    /**
     * Hands a media URL to the system's own player. The loopback server is already serving those
     * bytes, so VLC/mpv/a browser opens the URL directly — no temp copy and no transcode.
     */
    private void playExternally(String url) {
        if (hostServices == null) {
            status.setText("No external player available");
            return;
        }
        status.setText("Opening the video outside Insula…");
        hostServices.showDocument(url);
    }

    /**
     * Detects ffmpeg off-thread and re-renders any placeholder already on screen, so enabling the
     * feature (or installing ffmpeg) takes effect without reopening the article.
     */
    /**
     * Whether the BitTorrent transport can be used at all.
     *
     * <p>The {@code catch} is load-bearing rather than defensive: jlibtorrent is
     * {@code requires static}, so in a packaged build its classes are simply absent and merely
     * <em>touching</em> {@link TorrentTransport} throws {@link NoClassDefFoundError} before any
     * code inside it can run. HTTP is the guaranteed path either way.
     */
    private static boolean torrentTransportPresent() {
        try {
            return TorrentTransport.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyTranscodeSupport() {
        transcodes.configure(settings.getFfmpegPath(), settings.getFfprobePath());
        if (!settings.isVideoTranscode()) {
            transcodeAvailable = false;
            return;
        }
        transcodes.detect(found -> {
            boolean changed = found != transcodeAvailable;
            transcodeAvailable = found;
            if (settingsDialog != null) {
                settingsDialog.setVideoToolStatus(found);
            }
            if (changed && archive != null) {
                // The placeholder was built for the old answer; rebuild it against the new one.
                renderer.runScript("window.__insulaMediaDone = false;");
                installMediaFallback();
            }
        });
    }

    String currentArticlePathForTest() {
        return lastArticlePath;
    }

    boolean transcodeAvailableForTest() {
        return transcodeAvailable;
    }

    /**
     * Plays a video the engine cannot decode, starting almost immediately.
     *
     * <p>Rather than transcoding the whole file first (21 s for a 25-minute talk), the playlist is
     * written up front from a duration probe and segments are encoded as the player asks for them
     * — ~165 ms each, anywhere in the file, so a seek costs the same as a start and only what is
     * watched is ever encoded. ffmpeg reads the source straight off the loopback server, so the
     * original is never copied.
     */
    private void playInApp(String boxId, String sourceUrl) {
        if (server == null) {
            return;
        }
        renderer.runScript(MediaFallback.progressScript(boxId, 0, "Starting video…"));
        transcodes.openSession(
                sourceUrl,
                dataDir.resolve("media-work"),
                session -> {
                    disposeMediaSession();
                    mediaSession = session;
                    mediaStreamToken = server.registerStream(new ZimHttpServer.MediaStream() {
                        @Override
                        public String playlist(String segmentUriFormat) {
                            return session.playlist(segmentUriFormat);
                        }

                        @Override
                        public byte[] segment(int index) throws IOException {
                            return session.segment(index);
                        }
                    });
                    showVideoOverlay(server.streamUrl(mediaStreamToken), videoTitleFor(sourceUrl));
                    renderer.runScript(MediaFallback.progressScript(boxId, 100, "Playing above"));
                    status.setText("Playing");
                },
                message -> {
                    renderer.runScript(MediaFallback.progressScript(boxId, 0, "Could not play this video"));
                    status.setText(message);
                });
    }

    private HlsSession mediaSession;
    private String mediaStreamToken;
    private VideoPlayerPane videoOverlay;

    /**
     * Shows the player above the article rather than inside it. Inline playback of the on-demand
     * stream crashed WebKit's paint pulse about half the time (see {@link VideoPlayerPane}), and
     * WebView being single-process makes that fatal to the app.
     */
    private void showVideoOverlay(String streamUrl, String title) {
        closeVideoOverlay();
        videoOverlay = new VideoPlayerPane(streamUrl, title, this::closeVideoOverlay);
        root.getChildren().add(videoOverlay.node());
    }

    private void closeVideoOverlay() {
        if (videoOverlay != null) {
            root.getChildren().remove(videoOverlay.node());
            videoOverlay.dispose();
            videoOverlay = null;
        }
        disposeMediaSession();
    }

    boolean videoOverlayShowingForTest() {
        return videoOverlay != null;
    }

    /** The article's title reads better over a player than the URL of a blob inside an archive. */
    private String videoTitleFor(String sourceUrl) {
        ArticleRef ref = currentRef();
        return ref != null ? ref.title() : sourceUrl;
    }

    /** One video at a time: a session holds encoded segments, so an abandoned one must let go. */
    private void disposeMediaSession() {
        if (mediaSession != null) {
            mediaSession.dispose();
            mediaSession = null;
        }
        if (mediaStreamToken != null && server != null) {
            server.unregisterStream(mediaStreamToken);
            mediaStreamToken = null;
        }
    }

    HlsSession mediaSessionForTest() {
        return mediaSession;
    }

    // ---------------------------------------------------------------- Reader View

    ReaderViewSession readerViewForTest() {
        return readerView;
    }

    private ReaderView.Prefs readerViewPrefs() {
        return ReaderView.Prefs.normalized(
                settings.getReaderViewFont(),
                settings.getReaderViewFontSize(),
                settings.getReaderViewWidth(),
                settings.getReaderViewLineHeight(),
                settings.getReaderViewTheme());
    }

    private void toggleReaderView() {
        if (archive == null || surface != Surface.READER) {
            status.setText("Open an article first");
            return;
        }
        if (readerView.isActive()) {
            exitReaderView();
            return;
        }
        // Reader View owns the page's whole appearance, so the reader-theme user stylesheet is
        // lifted while it is active — its !important rules sit higher in the cascade than any
        // author style the shell could inject, and the two would otherwise fight over colors.
        renderer.injectCss("");
        if (readerView.enter(bookTitle, readerViewPrefs())) {
            setReaderViewChrome(true);
            status.setText("Reader View · " + ReaderView.readingTime(readerView.words()));
        } else {
            renderer.injectCss(
                    ReaderTheme.css(ReaderTheme.modeOf(settings.getReaderMode()), settings.getReaderWidth(), 1.0));
            status.setText("This page isn't suitable for Reader View");
        }
    }

    private void exitReaderView() {
        readerView.pageChanged();
        setReaderViewChrome(false);
        applyReaderTheme();
        // Reload restores the original document; the URL never changed, so history is untouched.
        renderer.engine().reload();
    }

    /**
     * Runs when a document has actually finished loading: asks Mozilla's readerable probe
     * whether the page is worth distilling and lights the toolbar button accordingly — the
     * same gesture as Firefox's reader icon appearing in the URL bar.
     */
    private void probeReaderable() {
        boolean readerable = readerView.probe();
        readerViewButton.setDisable(!readerable);
    }

    /** A navigation happened: whatever reader state existed belongs to the previous document. */
    private void resetReaderViewForNavigation() {
        closeVideoOverlay();
        if (readerView.isActive()) {
            readerView.pageChanged();
            setReaderViewChrome(false);
            applyReaderTheme();
        }
        readerViewButton.setDisable(true); // until the new page's probe says otherwise
    }

    private void setReaderViewChrome(boolean active) {
        typePanelButton.setVisible(active);
        typePanelButton.setManaged(active);
        if (!active && typePanel != null) {
            typePanel.hide();
        }
    }

    private void applyReaderViewPrefs() {
        settings.save();
        readerView.applyPrefs(readerViewPrefs());
    }

    // ---- the Aa panel: Firefox's type controls, as JavaFX so every knob is a real control ----

    private void toggleTypePanel() {
        if (typePanel != null && typePanel.isShowing()) {
            typePanel.hide();
            return;
        }
        typePanel = buildTypePanel();
        var bounds = typePanelButton.localToScreen(typePanelButton.getBoundsInLocal());
        typePanel.show(typePanelButton, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    private javafx.stage.Popup buildTypePanel() {
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);

        var serif = new javafx.scene.control.ToggleButton("Serif");
        serif.setStyle("-fx-font-family: 'Georgia', serif;");
        var sans = new javafx.scene.control.ToggleButton("Sans");
        var fontGroup = new javafx.scene.control.ToggleGroup();
        serif.setToggleGroup(fontGroup);
        sans.setToggleGroup(fontGroup);
        boolean isSans = ReaderView.FONT_SANS.equals(readerViewPrefs().font());
        (isSans ? sans : serif).setSelected(true);
        serif.setOnAction(e -> {
            settings.setReaderViewFont(ReaderView.FONT_SERIF);
            applyReaderViewPrefs();
        });
        sans.setOnAction(e -> {
            settings.setReaderViewFont(ReaderView.FONT_SANS);
            applyReaderViewPrefs();
        });
        HBox fontRow = new HBox(6, serif, sans);

        HBox sizeRow = stepperRow(
                "A\u2212",
                "A+",
                "Text size",
                () -> {
                    settings.setReaderViewFontSize(settings.getReaderViewFontSize() - ReaderView.FONT_STEP_PX);
                    applyReaderViewPrefs();
                },
                () -> {
                    settings.setReaderViewFontSize(settings.getReaderViewFontSize() + ReaderView.FONT_STEP_PX);
                    applyReaderViewPrefs();
                });
        HBox widthRow = stepperRow(
                "\u2192\u2190",
                "\u2190\u2192",
                "Width",
                () -> {
                    settings.setReaderViewWidth(settings.getReaderViewWidth() - ReaderView.WIDTH_STEP_PX);
                    applyReaderViewPrefs();
                },
                () -> {
                    settings.setReaderViewWidth(settings.getReaderViewWidth() + ReaderView.WIDTH_STEP_PX);
                    applyReaderViewPrefs();
                });
        HBox lineRow = stepperRow(
                "\u2261\u2212",
                "\u2261+",
                "Line spacing",
                () -> {
                    settings.setReaderViewLineHeight(settings.getReaderViewLineHeight() - ReaderView.LINE_HEIGHT_STEP);
                    applyReaderViewPrefs();
                },
                () -> {
                    settings.setReaderViewLineHeight(settings.getReaderViewLineHeight() + ReaderView.LINE_HEIGHT_STEP);
                    applyReaderViewPrefs();
                });

        var themeGroup = new javafx.scene.control.ToggleGroup();
        HBox themeRow = new HBox(6);
        for (String theme : new String[] {ReaderView.THEME_LIGHT, ReaderView.THEME_SEPIA, ReaderView.THEME_DARK}) {
            var swatch = new javafx.scene.control.ToggleButton(
                    theme.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + theme.substring(1));
            swatch.setToggleGroup(themeGroup);
            String colors =
                    switch (theme) {
                        case ReaderView.THEME_SEPIA -> "-fx-base: #f4ecd8; -fx-text-fill: #5b4636;";
                        case ReaderView.THEME_DARK -> "-fx-base: #1c1b22; -fx-text-fill: #fbfbfe;";
                        default -> "-fx-base: #ffffff; -fx-text-fill: #15141a;";
                    };
            swatch.setStyle(colors);
            if (theme.equals(readerViewPrefs().theme())) {
                swatch.setSelected(true);
            }
            swatch.setOnAction(e -> {
                settings.setReaderViewTheme(theme);
                applyReaderViewPrefs();
            });
            themeRow.getChildren().add(swatch);
        }

        VBox box = new VBox(10, fontRow, sizeRow, widthRow, lineRow, themeRow);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 12, 0, 0, 3);");
        popup.getContent().add(box);
        return popup;
    }

    private HBox stepperRow(String lessLabel, String moreLabel, String caption, Runnable less, Runnable more) {
        Button minus = new Button(lessLabel);
        Button plus = new Button(moreLabel);
        minus.setOnAction(e -> less.run());
        plus.setOnAction(e -> more.run());
        Label label = new Label(caption);
        label.setStyle("-fx-opacity: 0.7;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(6, label, gap, minus, plus);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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
        if (lanServer != null) {
            stopLanSharing();
        }
        closeVideoOverlay();
        transcodes.shutdown();
        try {
            rememberScrollForActiveTab();
            bookmarks.save();
            history.save();
        } catch (RuntimeException e) {
            // a failed store write must never block shutdown, as with reading positions
        }
        downloads.close();
        catalogCache.close();
        iconCache.close();
        closeArchive();
        if (server != null) {
            server.close();
        }
    }
}
