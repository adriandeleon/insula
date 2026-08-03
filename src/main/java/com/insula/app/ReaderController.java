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
import com.insula.catalog.CatalogFilter;
import com.insula.catalog.StarterPicks;
import com.insula.catalog.UpdateCheck;
import com.insula.catalog.ZimEntry;
import com.insula.command.Command;
import com.insula.command.CommandRegistry;
import com.insula.command.Keybindings;
import com.insula.config.Settings;
import com.insula.download.DownloadManager;
import com.insula.download.HttpMultiSourceTransport;
import com.insula.download.TorrentTransport;
import com.insula.download.TransportSelector;
import com.insula.library.ArchiveMove;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;
import com.insula.library.Shelf;
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
    private final Button readerViewButton = new Button("Reader View");
    private final Button typePanelButton = new Button("Aa");
    private javafx.stage.Popup typePanel;
    private ReaderViewSession readerView;
    private final javafx.scene.control.ToggleButton homeTab = new javafx.scene.control.ToggleButton("Home");
    private final javafx.scene.control.ToggleButton libraryTab = new javafx.scene.control.ToggleButton("Library");
    private final javafx.scene.control.ToggleButton catalogTab = new javafx.scene.control.ToggleButton("Catalog");
    private final javafx.scene.control.ToggleButton readerTab = new javafx.scene.control.ToggleButton("Reader");
    private HomePane homePane;
    private final Label statusArchive = new Label();
    private final Label statusArriving = new Label();
    private final Label statusLan = new Label();
    private final TextField omniField = new TextField();
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
    private javafx.scene.control.MenuBar menuBar;
    private Region readerPane;
    private VBox tabContent;
    private final javafx.scene.control.TabPane tabBar = new javafx.scene.control.TabPane();
    private final HBox restoredBar = new HBox(10);
    private final Label restoredLabel = new Label();
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
    private final CatalogPane catalogPane;
    private final Library library;
    private final TransportSelector transports;
    private final DownloadManager downloads;
    private final LibraryPane libraryPane;
    /** Cross-archive search: every archive in the library, not just the one being read. */
    private final LibrarySearch librarySearch = new LibrarySearch();

    private final java.util.Map<Path, ZimArchive> searchArchives = new java.util.LinkedHashMap<>();
    private final ReadingPositions positions;
    private final com.insula.reader.ReaderSession session;
    /** The article currently shown, for saving its scroll position when we navigate away. */
    private String currentPositionKey;

    private SplitPane readerSplit;
    private VBox[] sidebarPanes;
    private VBox sidebarHost;

    /** Which surface fills the window: the Reader, the Library (home), or the Catalog. */
    enum Surface {
        HOME,
        READER,
        LIBRARY,
        CATALOG
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
        this.session = com.insula.reader.ReaderSession.load(dataDir.resolve("session.txt"));
        this.bookmarks = ArticleStore.load(dataDir.resolve("bookmarks.properties"), MAX_BOOKMARKS);
        this.history = ArticleStore.load(dataDir.resolve("history.properties"), MAX_HISTORY);
        this.transports = new TransportSelector(new HttpMultiSourceTransport());
        // BitTorrent is an option, never the only way: it is registered only when its native
        // library actually loads, and HTTP stays the fallback regardless.
        if (torrentTransportPresent()) {
            transports.register(new TorrentTransport(settings.isSeedingEnabled()));
        }
        this.downloads = new DownloadManager(transports, library, settings.getArchivesFolder(defaultArchivesDir()));
        downloads.setOnAdmitted(job -> Platform.runLater(() -> afterArchiveAdmitted(job)));
        this.transcodes = new TranscodeService();
        this.catalogCache = new CatalogCache(dataDir.resolve("catalog"));
        this.iconCache = new IconCache(dataDir.resolve("catalog/icons"));
        this.catalogPane = new CatalogPane(
                catalogCache,
                iconCache,
                (entry, title) -> {
                    downloads.enqueue(entry);
                    status.setText("Downloading " + title);
                },
                this::catalogCardState,
                this::installedFileFor,
                this::openFromLibrary,
                status::setText,
                () -> freeDiskBytes(dataDir));
        this.libraryPane =
                new LibraryPane(downloads, library, this::openFromLibrary, status::setText, () -> diskInfo(dataDir));
        this.homePane = new HomePane(
                bookmarks,
                history,
                downloads,
                this::lastReadArticle,
                this::homeSearchScope,
                ref -> openRef(ref, false),
                () -> commands.run("view.commandPalette"),
                this::showLibrary);
        homePane.setEmptyDevice(() -> library.entries().isEmpty());
        homePane.setFirstRunActions(
                () -> commands.run("file.open"), () -> catalogCache.entries().size());
        libraryPane.setSeedsSupplier(this::activeSeeds);
        wireLibraryOrganization();
        wireLanRow();
        // Commands and their chords first: the menubar is built *out of* them, so building the UI
        // beforehand leaves every menu holding nothing but its separators. Neither of these two
        // touches a node — every command body is a lambda evaluated when it runs — so the order
        // costs nothing.
        registerCommands();
        bindKeys();
        buildUi();
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

    void closeArchiveForTest() {
        closeArchive();
    }

    CatalogPane.CardState catalogCardStateForTest(ZimEntry entry) {
        return catalogCardState(entry);
    }

    DownloadManager downloadsForTest() {
        return downloads;
    }

    SplitPane readerSplitForTest() {
        return readerSplit;
    }

    Region sidebarHostForTest() {
        return sidebarHost;
    }

    javafx.scene.control.MenuBar menuBarForTest() {
        return menuBar;
    }

    javafx.scene.control.TabPane tabBarForTest() {
        return tabBar;
    }

    void openRefForTest(ArticleRef ref, boolean inNewTab) {
        openRef(ref, inNewTab);
    }

    String statusTextForTest() {
        return status.getText() == null ? "" : status.getText();
    }

    ListView<ArticleRef> bookmarkListForTest() {
        return bookmarkList;
    }

    void refreshBookmarkListForTest() {
        refreshBookmarkList();
    }

    java.util.Set<Path> missingArchivesForTest() {
        return java.util.Set.copyOf(missingArchives);
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

    CatalogPane catalogPaneForTest() {
        return catalogPane;
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
        commands.register("home.open", "Show Home", this::showHome);
        commands.register("library.open", "Show the Library", this::showLibrary);
        commands.register("tab.reopen", "Reopen Closed Tab", this::reopenClosedTab);
        commands.register("file.closeArchive", "Close Archive", this::closeCurrentArchive);
        commands.register("library.reveal", "Reveal Archives Folder", this::revealArchivesFolder);
        commands.register(
                "download.pauseAll",
                "Pause All Downloads",
                () -> eachActiveDownload(DownloadManager.Job::pause, "Paused all downloads"));
        commands.register(
                "download.resumeAll",
                "Resume All Downloads",
                () -> eachActiveDownload(DownloadManager.Job::resume, "Resumed all downloads"));
        commands.register("view.toggleSidebar", "Show/Hide Sidebar", this::toggleSidebar);
        commands.register("view.sidebarSide", "Move Sidebar Left/Right", this::toggleSidebarSide);
        commands.register("library.import", "Import Archive into Library…", this::importArchive);
        commands.register("catalog.starterPicks", "Starter Picks", this::showStarterPicks);
        commands.register("help.zimFormat", "What's in a ZIM File?", this::showZimExplainer);
        commands.register("help.shortcuts", "Keyboard Shortcuts", this::showShortcuts);
        commands.register("help.about", "About Insula", this::showAbout);
        commands.register("app.quit", "Quit Insula", () -> stage.close());
        commands.register("catalog.open", "Show the Catalog", this::showCatalog);
        commands.register("library.show", "Show Library and Downloads", this::showLibrary);
        commands.register("library.reader", "Back to Reader", this::showReader);
        commands.register("library.toggle", "Toggle Library / Reader", this::toggleLibrary);
        commands.register("library.checkUpdates", "Check for Archive Updates", () -> checkForUpdates(true));
        commands.register("library.updateAll", "Update All Archives", this::updateAllArchives);
        commands.register("library.repairAll", "Repair Quarantined Archives", this::repairAllQuarantined);
        commands.register("lan.share", "Share Library on Local Network", this::toggleLanSharing);
        commands.register("lan.qr", "Show LAN Sharing QR Code", () -> {
            if (lanUrl != null) {
                showLanShareWindow(lanUrl);
            } else {
                status.setText("Not sharing — start sharing from the Library first");
            }
        });
        commands.register("library.search", "Search the Online Catalog", () -> {
            showCatalog();
            catalogPane.focusSearch();
        });
        commands.register("catalog.refresh", "Refresh the Catalog", () -> {
            showCatalog();
            catalogPane.refreshCommand();
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
        keys.bind("search.focus", new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        keys.bind("nav.back", new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN));
        keys.bind("nav.forward", new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN));
        keys.bind("view.zoomIn", new KeyCodeCombination(KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.zoomOut", new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.zoomReset", new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN));
        keys.bind("home.open", new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN));
        keys.bind("library.open", new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN));
        keys.bind("catalog.open", new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN));
        keys.bind("catalog.refresh", new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN));
        keys.bind("lan.share", new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN));
        keys.bind("app.quit", new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        keys.bind("help.shortcuts", new KeyCodeCombination(KeyCode.SLASH, KeyCombination.SHORTCUT_DOWN));
        keys.bind("view.toggleSidebar", new KeyCodeCombination(KeyCode.BACK_SLASH, KeyCombination.SHORTCUT_DOWN));
        keys.bind(
                "tab.reopen",
                new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        // reader.cycleMode is deliberately unbound: the kit gives Ctrl+R to the catalog refresh,
        // and leaving both bound made one of them silently unreachable.
        keys.bind(
                "readerview.toggle",
                new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        keys.bind("tab.new", new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        keys.bind("tab.close", new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        keys.bind("tab.next", new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN));
        keys.bind(
                "tab.previous",
                new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        keys.bind("bookmark.toggle", new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN));
        keys.bind("bookmark.show", new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
        keys.bind("history.show", new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN));
    }

    void installShortcuts(Scene scene) {
        keys.install(scene, commands);
        Fonts.load();
        scene.getStylesheets().add(getClass().getResource("insula.css").toExternalForm());
        scene.getStylesheets().add(getClass().getResource("app.css").toExternalForm());
        watchSystemTheme();
        applySettings(); // the scene exists now, so the theme class can actually land
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        readerView = new ReaderViewSession(renderer::runScript);
        backButton.setOnAction(e -> commands.run("nav.back"));
        forwardButton.setOnAction(e -> commands.run("nav.forward"));
        backButton.setDisable(true);
        forwardButton.setDisable(true);

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

        // Surfaces: the kit's segmented switcher. Reader only appears once something is open.
        javafx.scene.control.ToggleGroup surfaceGroup = new javafx.scene.control.ToggleGroup();
        homeTab.setToggleGroup(surfaceGroup);
        libraryTab.setToggleGroup(surfaceGroup);
        catalogTab.setToggleGroup(surfaceGroup);
        readerTab.setToggleGroup(surfaceGroup);
        homeTab.setOnAction(e -> commands.run("home.open"));
        libraryTab.setOnAction(e -> commands.run("library.open"));
        catalogTab.setOnAction(e -> commands.run("catalog.open"));
        readerTab.setOnAction(e -> commands.run("library.reader"));
        readerTab.setVisible(false);
        readerTab.setManaged(false);
        HBox surfaces = new HBox(homeTab, libraryTab, catalogTab, readerTab);
        surfaces.getStyleClass().add("surfaces");
        surfaces.setAlignment(Pos.CENTER_LEFT);

        Button paletteButton = new Button("⌘K");
        paletteButton.setTooltip(new javafx.scene.control.Tooltip("Command palette"));
        paletteButton.setOnAction(e -> commands.run("view.commandPalette"));

        // One omnibox, whose placeholder states the scope and the count of what it searches.
        omniField.setPromptText("Search");
        omniField.getStyleClass().add("omni");
        omniField.setOnAction(e -> commands.run("view.commandPalette"));
        omniField.setEditable(false);
        omniField.setFocusTraversable(false);
        omniField.setOnMouseClicked(e -> commands.run("view.commandPalette"));
        // Wide enough to actually say the scope: an elided "Search everything you have — 17 archi"
        // defeats the point of a placeholder that names what it searches.
        omniField.setPrefWidth(460);
        omniField.setMinWidth(320);
        omniField.setMaxWidth(560);

        Region leftGap = new Region();
        Region rightGap = new Region();
        HBox.setHgrow(leftGap, Priority.ALWAYS);
        HBox.setHgrow(rightGap, Priority.ALWAYS);
        // Open, Home and Settings are gone: the File menu opens archives and Settings, the
        // surface switcher already carries Home, and back/forward moved onto the tab strip where
        // they belong — they are that tab's history, not the window's.
        ToolBar toolbar = new ToolBar(
                surfaces,
                leftGap,
                omniField,
                rightGap,
                readerViewButton,
                typePanelButton,
                bookmarkButton,
                paletteButton);

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
                // Why this result is here, read back out of its own score, so the caption can
                // never disagree with the ranking that put it in this position.
                String tier = com.insula.search.MatchScore.tierOf(item.score()).caption();
                Label source = new Label(tier.isEmpty() ? item.archiveTitle() : item.archiveTitle() + " · " + tier);
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

        buildRestoredBar();
        // Back/forward ride the tab strip rather than the window toolbar: they are *this tab's*
        // history, and a browser puts them beside the tab for the same reason. Aligned to the top
        // of the row so they sit on the strip's line rather than floating beside the article.
        for (Button nav : List.of(backButton, forwardButton)) {
            nav.getStyleClass().add("tab-nav");
        }
        // Below the tab strip rather than beside it. Beside, they sat in a gap before the first
        // tab and pushed every tab right by their full width for the whole session; below, they
        // cost one thin row and share it with the restore notice, which is the other per-article
        // thing that belongs between the strip and the page. It is also where a browser puts
        // them — tabs on top, navigation under.
        HBox tabNav = new HBox(2, backButton, forwardButton);
        tabNav.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(restoredBar, Priority.ALWAYS);
        HBox navRow = new HBox(8, tabNav, restoredBar);
        navRow.setAlignment(Pos.CENTER_LEFT);
        navRow.getStyleClass().add("tab-nav-row");

        // The row rides *inside* the showing tab, above the article. A TabPane draws its header
        // and content as one unit, so the only way under the strip is to be part of the content —
        // which is also the honest place for it, since this is the tab's own navigation.
        VBox.setVgrow(renderer.node(), Priority.ALWAYS);
        tabContent = new VBox(navRow, renderer.node());
        javafx.scene.layout.BorderPane readerSide = new javafx.scene.layout.BorderPane(tabBar);
        readerPane = readerSide;
        readerSplit = new SplitPane();
        readerSplit.setOrientation(Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(sidebar, false);
        applySidebarLayout();

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

        // The kit's status bar: the archive's verified flag, arriving downloads (click → Library),
        // and LAN state. Quiet facts only — nothing here pushes per event.
        statusArriving.setOnMouseClicked(e -> commands.run("library.open"));
        for (Label label : List.of(statusArchive, statusArriving, statusLan)) {
            label.getStyleClass().add("card-faint");
        }
        Region statusGap = new Region();
        HBox.setHgrow(statusGap, Priority.ALWAYS);
        HBox statusBar = new HBox(18, status, statusGap, statusArchive, statusArriving, statusLan);
        statusBar.setPadding(new Insets(4, 10, 4, 10));

        menuBar = Menus.build(commands, keys, menuDynamics());
        VBox topChrome = new VBox(menuBar, toolbar);
        shell.setTop(topChrome);
        shell.setCenter(readerSplit);
        shell.setBottom(statusBar);
        root.getChildren().add(shell);
    }

    // ---------------------------------------------------------------- library organization

    private void wireLibraryOrganization() {
        libraryPane.setOrganizeHandlers(
                (groupBy, sortBy) -> {
                    settings.setLibraryGroupBy(groupBy.name());
                    settings.setLibrarySortBy(sortBy.name());
                    settings.save();
                },
                reordered -> {
                    reordered.forEach(library::replace);
                    library.save();
                    libraryPane.activate();
                },
                entry -> {
                    library.replace(entry.withPinned(!entry.pinned()));
                    library.save();
                    libraryPane.activate();
                    status.setText(entry.pinned() ? "Unpinned " + entry.title() : "Pinned " + entry.title());
                });
        libraryPane.setRowActions(new LibraryPane.RowActions() {
            @Override
            public void openInNewTab(LibraryEntry entry) {
                openArchiveInNewTab(entry);
            }

            @Override
            public void moveToTheme(LibraryEntry entry) {
                promptTheme(entry);
            }

            @Override
            public void shareOnLan(LibraryEntry entry) {
                commands.run("lan.share");
            }

            @Override
            public void checkForUpdate(LibraryEntry entry) {
                checkForUpdates(true);
            }

            @Override
            public void verifyNow(LibraryEntry entry) {
                status.setText("Verification on demand is not available yet for " + entry.title());
            }

            @Override
            public void reveal(LibraryEntry entry) {
                if (hostServices != null) {
                    hostServices.showDocument(entry.file().getParent().toUri().toString());
                }
            }

            @Override
            public void delete(LibraryEntry entry) {
                confirmDeleteArchive(entry);
            }
        });
        libraryPane.setArrangement(groupByPreference(), sortByPreference());
    }

    private Shelf.GroupBy groupByPreference() {
        try {
            return Shelf.GroupBy.valueOf(settings.getLibraryGroupBy());
        } catch (IllegalArgumentException e) {
            return Shelf.GroupBy.THEME;
        }
    }

    private Shelf.SortBy sortByPreference() {
        try {
            return Shelf.SortBy.valueOf(settings.getLibrarySortBy());
        } catch (IllegalArgumentException e) {
            return Shelf.SortBy.CUSTOM;
        }
    }

    private void openArchiveInNewTab(LibraryEntry entry) {
        showReader();
        openZim(entry.file());
    }

    /** Moving an archive to a theme is the override that keeps tagging optional. */
    private void promptTheme(LibraryEntry entry) {
        javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog(
                entry.theme().isBlank() ? Shelf.themeOf(entry.fileName()) : entry.theme());
        dialog.setTitle("Move to theme");
        dialog.setHeaderText("Group " + entry.title() + " under:");
        dialog.setContentText("Theme");
        dialog.showAndWait().ifPresent(theme -> {
            library.replace(entry.withTheme(theme.strip()));
            library.save();
            libraryPane.activate();
        });
    }

    private void confirmDeleteArchive(LibraryEntry entry) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Delete " + entry.fileName() + " (" + Formats.bytes(entry.sizeBytes())
                        + ") from disk? This cannot be undone.",
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        alert.setHeaderText("Delete this archive?");
        if (alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        try {
            if (currentArchiveFile != null && currentArchiveFile.equals(entry.file())) {
                closeArchive();
            }
            java.nio.file.Files.deleteIfExists(entry.file());
            library.remove(entry.file());
            library.save();
            librarySearch.remove(entry.file());
            libraryPane.activate();
            status.setText("Deleted " + entry.fileName());
        } catch (IOException e) {
            status.setText("Could not delete " + entry.fileName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- shell commands

    /** Closed tabs, newest first, so Ctrl+Shift+T walks back through them. */
    private final java.util.Deque<ArticleRef> closedTabs = new java.util.ArrayDeque<>();

    private static final int MAX_CLOSED_TABS = 20;

    private void reopenClosedTab() {
        if (closedTabs.isEmpty()) {
            status.setText("No recently closed tabs");
            return;
        }
        openRef(closedTabs.pop(), true);
    }

    private void rememberClosedTab(ArticleRef ref) {
        if (ref == null) {
            return;
        }
        closedTabs.push(ref);
        while (closedTabs.size() > MAX_CLOSED_TABS) {
            closedTabs.removeLast();
        }
    }

    int closedTabCountForTest() {
        return closedTabs.size();
    }

    private void closeCurrentArchive() {
        if (archive == null) {
            status.setText("No archive is open");
            return;
        }
        closeArchive();
        lastArticlePath = null;
        bookTitle = "";
        showLibrary();
        syncNavTabs();
        status.setText("Closed the archive");
    }

    private void revealArchivesFolder() {
        Path folder = archivesDir();
        try {
            java.nio.file.Files.createDirectories(folder);
        } catch (IOException e) {
            status.setText("Could not open the archives folder: " + e.getMessage());
            return;
        }
        if (hostServices != null) {
            hostServices.showDocument(folder.toUri().toString());
        } else {
            status.setText(folder.toString());
        }
    }

    private void eachActiveDownload(java.util.function.Consumer<DownloadManager.Job> action, String message) {
        List<DownloadManager.Job> active = downloads.jobs().stream()
                .filter(job -> !job.snapshot().state().isTerminal())
                .toList();
        if (active.isEmpty()) {
            status.setText("Nothing is downloading");
            return;
        }
        active.forEach(action);
        status.setText(message);
    }

    private void showAbout() {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("About Insula");
        alert.setHeaderText("Insula");
        alert.setContentText("An offline reader for ZIM archives — whole encyclopedias, courses and "
                + "talks stored as single files on your disk.\n\nArchives live in "
                + archivesDir() + ".");
        alert.showAndWait();
    }

    /** The shortcut sheet is the keymap itself, so it can never drift from what is bound. */
    private void showShortcuts() {
        StringBuilder text = new StringBuilder();
        commands.all().stream()
                .filter(c -> !keys.displayFor(c.id()).isBlank())
                .sorted(java.util.Comparator.comparing(Command::title))
                .forEach(c -> text.append(String.format("%-28s %s%n", keys.displayFor(c.id()), c.title())));
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("Keyboard shortcuts");
        alert.setHeaderText("Keyboard shortcuts");
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text.toString());
        area.setEditable(false);
        area.setPrefRowCount(18);
        area.setStyle("-fx-font-family: monospace;");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
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
        HBox.setHgrow(row, Priority.ALWAYS);

        // The panel's own controls sit on the panel: a pane you can only close from a menu you
        // have to go looking for is one most people never close.
        Button side = new Button("⇄");
        side.getStyleClass().add("sidebar-control");
        side.setTooltip(new javafx.scene.control.Tooltip("Move the sidebar to the other side"));
        side.setOnAction(e -> commands.run("view.sidebarSide"));
        Button close = new Button("✕");
        close.getStyleClass().add("sidebar-control");
        close.setTooltip(new javafx.scene.control.Tooltip("Hide the sidebar (Ctrl+\\)"));
        close.setOnAction(e -> commands.run("view.toggleSidebar"));

        HBox header = new HBox(4, row, side, close);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
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
                    // A bookmark outlives its archive by design, so one whose file is gone is
                    // shown greyed with the reason rather than hidden: hiding it looks like the
                    // bookmark was lost, when in fact plugging the drive back in restores it.
                    boolean installed = missingArchives.stream().noneMatch(p -> p.equals(item.archiveFile()));
                    Label source = new Label(installed ? item.bookTitle() : item.bookTitle() + " · not installed");
                    source.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.85em;");
                    VBox box = new VBox(1, title, source);
                    box.setOpacity(installed ? 1 : 0.45);
                    setGraphic(box);
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

    /**
     * Archives referenced by a saved article but not on disk. Recomputed when a list is refreshed
     * rather than probed per cell: a cell factory runs on every scroll, and a {@code Files.exists}
     * per row per frame would put the filesystem on the scroll path.
     */
    private final java.util.Set<Path> missingArchives = new java.util.HashSet<>();

    private void refreshMissingArchives(List<ArticleRef> refs) {
        missingArchives.clear();
        refs.stream()
                .map(ArticleRef::archiveFile)
                .distinct()
                .filter(f -> !java.nio.file.Files.isRegularFile(f))
                .forEach(missingArchives::add);
    }

    private void refreshBookmarkList() {
        List<ArticleRef> entries = bookmarks.entries();
        refreshMissingArchives(entries);
        bookmarkList.getItems().setAll(entries);
    }

    private void refreshHistoryList() {
        List<ArticleRef> entries = history.entries();
        refreshMissingArchives(entries);
        historyList.getItems().setAll(entries);
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
        ReaderTabs.Tab closing = tabs.active();
        if (closing != null) {
            rememberClosedTab(closing.article());
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
                javafx.scene.control.Tab uiTab = new javafx.scene.control.Tab();
                // The label lives in the graphic so an archive chip can sit beside it; the text is
                // left empty rather than duplicated, which would render the title twice.
                Label label = new Label(tab.label());
                String chip = tabs.chipFor(tab);
                if (chip.isBlank()) {
                    uiTab.setGraphic(label);
                } else {
                    Label chipLabel = new Label(chip);
                    chipLabel.getStyleClass().addAll("pill", "pill-neutral");
                    uiTab.setGraphic(new HBox(6, label, chipLabel));
                }
                tabModel.put(uiTab, tab);
                tabBar.getTabs().add(uiTab);
            }
            if (tabs.activeIndex() >= 0 && tabs.activeIndex() < tabBar.getTabs().size()) {
                javafx.scene.control.Tab selected = tabBar.getTabs().get(tabs.activeIndex());
                selected.setContent(tabContent);
                tabBar.getSelectionModel().select(selected);
            }
        } finally {
            syncingTabs = false;
        }
        rememberSession();
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
        rememberClosedTab(tab.article());
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
            if (other != uiTab && other.getContent() == tabContent) {
                other.setContent(null);
            }
        }
        uiTab.setContent(tabContent);
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
        // The title moved, so the strip is rebuilt rather than poked: a new article can change
        // whether chips are warranted at all.
        syncTabBar();
    }

    /**
     * Records the open set so a restart can reopen it. Called wherever the strip changes rather
     * than only at shutdown, because a crash or a kill is exactly when losing the session stings.
     */
    private void rememberSession() {
        session.set(
                tabs.tabs().stream()
                        .map(ReaderTabs.Tab::article)
                        .filter(java.util.Objects::nonNull)
                        .toList(),
                tabs.activeIndex());
        session.save();
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
            if (surface == Surface.CATALOG) {
                // leaving the store costs nothing; its state is plain fields
            }
            catalogPane.deactivate();
            shell.setCenter(libraryPane.node());
            libraryPane.activate();
            surface = Surface.LIBRARY;
            syncNavTabs();
            refreshAttention();
            checkForUpdates(false); // starters + update pills, off-thread, disk cache only
        }
    }

    private void showCatalog() {
        if (surface != Surface.CATALOG) {
            libraryPane.deactivate();
            shell.setCenter(catalogPane.node());
            catalogPane.activate();
            surface = Surface.CATALOG;
            syncNavTabs();
        }
    }

    private void showReader() {
        if (surface != Surface.READER) {
            libraryPane.deactivate();
            catalogPane.deactivate();
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

    /**
     * Where an idle startup lands. The kit makes <b>Home</b> the answer to "what do I read now?",
     * so the Library — the management surface — is no longer the front door.
     */
    void landOnLibraryIfIdle() {
        if (archive == null) {
            showHome();
        }
    }

    private void syncNavTabs() {
        homeTab.setSelected(surface == Surface.HOME);
        libraryTab.setSelected(surface == Surface.LIBRARY);
        catalogTab.setSelected(surface == Surface.CATALOG);
        readerTab.setSelected(surface == Surface.READER);
        // Reader is a surface only once there is something to read.
        boolean readable = archive != null;
        readerTab.setVisible(readable);
        readerTab.setManaged(readable);
        refreshStatusBar();
        refreshOmniPlaceholder();
    }

    private void showHome() {
        if (surface != Surface.HOME) {
            libraryPane.deactivate();
            catalogPane.deactivate();
            shell.setCenter(homePane.node());
            homePane.activate();
            surface = Surface.HOME;
            syncNavTabs();
        }
    }

    /** The omnibox says what it searches and how much of it — the kit's contextual placeholder. */
    private void refreshOmniPlaceholder() {
        omniField.setPromptText(
                switch (surface) {
                    case CATALOG -> "Search the catalog — instant, offline";
                    case READER ->
                        archive == null
                                ? "Search"
                                : "Search this archive — "
                                        + String.format(java.util.Locale.ROOT, "%,d", archive.entryCount())
                                        + " articles";
                    default -> homeSearchScope();
                });
    }

    /** "Search everything you have — N articles in M archives". */
    private String homeSearchScope() {
        int archives = library.verifiedEntries().size();
        long articles = librarySearch.indexedTitleCount();
        if (archives == 0) {
            return "Search everything you have — no archives yet";
        }
        return articles > 0
                ? String.format(
                        java.util.Locale.ROOT,
                        "Search everything you have — %,d articles in %d archive%s",
                        articles,
                        archives,
                        archives == 1 ? "" : "s")
                : String.format(
                        java.util.Locale.ROOT,
                        "Search everything you have — %d archive%s",
                        archives,
                        archives == 1 ? "" : "s");
    }

    private void refreshStatusBar() {
        statusArchive.setText(
                archive == null ? "" : bookTitle + (library.isVerified(currentArchiveFile) ? " · verified ✓" : ""));
        long arriving = downloads.jobs().stream()
                .filter(job -> !job.snapshot().state().isTerminal())
                .count();
        statusArriving.setText(arriving == 0 ? "" : arriving + " download" + (arriving == 1 ? "" : "s") + " arriving");
        statusLan.setText(lanServer == null ? "" : "LAN: sharing " + lanServer.sharedCount() + " archives");
    }

    /** The article to resume, for Home's Continue card. */
    private HomePane.Continue lastReadArticle() {
        List<ArticleRef> recent = history.entries();
        if (recent.isEmpty()) {
            return null;
        }
        ArticleRef ref = recent.getFirst();
        double fraction = positions.positionOf(ReadingPositions.key(ref.archiveFile(), ref.articlePath()));
        return new HomePane.Continue(ref, fraction, "");
    }

    boolean readerSurfaceVisibleForTest() {
        return readerTab.isVisible();
    }

    String omniPlaceholderForTest() {
        return omniField.getPromptText();
    }

    HomePane homePaneForTest() {
        return homePane;
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
        applyStarters(starters);
    }

    /** Both first-run surfaces show the same picks, so they are pushed together. */
    private void applyStarters(List<StarterPicks.Resolved> starters) {
        libraryPane.setStarters(starters, this::downloadUpdate, this::showCatalog);
        homePane.setStarters(starters, this::downloadUpdate, this::showCatalog);
        if (surface == Surface.HOME) {
            homePane.activate();
        }
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
                        applyStarters(starters);
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
        com.insula.library.UpdateReplacement.Policy policy = settings.getUpdatePolicy();
        Path installed = library.entries().stream()
                .filter(e -> e.fileName().equals(newName))
                .map(com.insula.library.LibraryEntry::file)
                .findFirst()
                .orElse(null);
        List<com.insula.library.LibraryEntry> superseded = library.entries().stream()
                .filter(e -> UpdateCheck.supersedes(newName, e.fileName()))
                .filter(e -> currentArchiveFile == null || !e.file().equals(currentArchiveFile))
                // The replacement must be verified and be a different file; both guards live in
                // the pure decision so the same rule governs the prompt and the silent path.
                .filter(e -> com.insula.library.UpdateReplacement.shouldDelete(
                        policy, e.file(), installed, library.isVerified(installed)))
                .toList();
        if (!superseded.isEmpty()) {
            List<String> names = superseded.stream()
                    .map(com.insula.library.LibraryEntry::fileName)
                    .toList();
            long bytes = superseded.stream()
                    .mapToLong(com.insula.library.LibraryEntry::sizeBytes)
                    .sum();
            if (!policy.confirms() || supersededConfirmer.test(names, bytes)) {
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
        // The Catalog card for this archive is now "In library"; say so without waiting for a
        // sampler tick, which may be the one that decides to stop.
        catalogPane.refreshStates();
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

    /**
     * Which archives to share, session-only. Empty means "everything verified" — the default the
     * command has always had, kept as the meaning of an untouched selection so the Library row and
     * the Ctrl+L command can never disagree about what sharing does.
     */
    private final java.util.Set<Path> lanSelection = new java.util.LinkedHashSet<>();

    private String lanUrl;

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
        List<com.insula.library.LibraryEntry> entries = library.verifiedEntries().stream()
                .filter(e -> lanSelection.isEmpty() || lanSelection.contains(e.file()))
                .toList();
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
        lanUrl = url;
        refreshLanRow();
        status.setText("Sharing " + sharedCount + (sharedCount == 1 ? " archive at " : " archives at ") + url);
        return url;
    }

    void stopLanSharing() {
        lanUrl = null;
        refreshLanRow();
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

    private void refreshLanRow() {
        libraryPane.setLanState(lanUrl, lanSelection.size());
    }

    private void wireLanRow() {
        libraryPane.setLanActions(new LibraryPane.LanActions() {
            @Override
            public void toggle() {
                toggleLanSharing();
            }

            @Override
            public void showQr() {
                if (lanUrl != null) {
                    showLanShareWindow(lanUrl);
                }
            }

            @Override
            public void chooseArchives() {
                chooseLanArchives();
            }
        });
        refreshLanRow();
    }

    /**
     * Picking archives to share. A restart of the server is forced when the choice changes while
     * sharing is live — silently serving the old set would make the row lie.
     */
    private void chooseLanArchives() {
        List<com.insula.library.LibraryEntry> verified = library.verifiedEntries();
        if (verified.isEmpty()) {
            status.setText("Nothing to share — the library has no verified archives");
            return;
        }
        VBox box = new VBox(6);
        List<javafx.scene.control.CheckBox> boxes = new java.util.ArrayList<>();
        for (com.insula.library.LibraryEntry entry : verified) {
            javafx.scene.control.CheckBox check =
                    new javafx.scene.control.CheckBox(entry.title() + "  (" + Formats.bytes(entry.sizeBytes()) + ")");
            check.setSelected(lanSelection.isEmpty() || lanSelection.contains(entry.file()));
            check.setUserData(entry.file());
            boxes.add(check);
            box.getChildren().add(check);
        }
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Choose archives to share");
        dialog.setHeaderText("Other devices will see only what is ticked.");
        dialog.getDialogPane().setContent(new javafx.scene.control.ScrollPane(box));
        dialog.getDialogPane()
                .getButtonTypes()
                .addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        List<Path> chosen = boxes.stream()
                .filter(javafx.scene.control.CheckBox::isSelected)
                .map(c -> (Path) c.getUserData())
                .toList();
        lanSelection.clear();
        // All ticked is the same as no choice at all, and storing it that way keeps a later
        // download automatically shared rather than silently excluded.
        if (chosen.size() != verified.size()) {
            lanSelection.addAll(chosen);
        }
        if (lanServer != null) {
            stopLanSharing();
            startLanSharing();
        } else {
            refreshLanRow();
        }
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
        // A secondary window is its own scene, so the design tokens must be attached here too —
        // without them it renders in raw defaults, which is how the old Settings shipped unthemed.
        Scene lanScene = new Scene(box);
        lanScene.getStylesheets().add(getClass().getResource("insula.css").toExternalForm());
        if (settings.isDark()) {
            box.getStyleClass().add("insula-dark");
        }
        lanShareWindow.setScene(lanScene);
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
        try (var stream = Files.list(archivesDir())) {
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

    /**
     * What the Catalog should show for an entry: the live download when one exists, otherwise
     * whether it is on disk. Sharing this with the Library is what keeps the two surfaces from
     * describing the same archive differently.
     */
    private CatalogPane.CardState catalogCardState(ZimEntry entry) {
        CatalogPane.Installed installed = installedStateFor(entry);
        DownloadManager.Job job = downloads.job(entry);
        if (job != null) {
            return new CatalogPane.CardState(installed, job.snapshot(), job::pause);
        }
        return CatalogPane.CardState.of(installed);
    }

    /**
     * Whether the entry is on disk — and if so, whether this catalog build is newer than what is
     * there. Uses the same name-based supersede test as the Library's update pills, so the two
     * surfaces cannot disagree about whether an archive is current.
     */
    private CatalogPane.Installed installedStateFor(ZimEntry entry) {
        if (installedFileFor(entry) != null) {
            return CatalogPane.Installed.YES;
        }
        boolean supersedesSomething =
                library.entries().stream().anyMatch(e -> UpdateCheck.supersedes(entry.fileName(), e.fileName()));
        return supersedesSomething ? CatalogPane.Installed.OUTDATED : CatalogPane.Installed.NO;
    }

    /** The verified installed file matching this catalog entry's (name, flavour), or null. */
    private Path installedFileFor(ZimEntry entry) {
        String base = CatalogFilter.installedBaseOf(entry.fileName());
        return library.verifiedEntries().stream()
                .filter(e -> CatalogFilter.installedBaseOf(e.fileName()).equals(base))
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
        downloads.setConcurrency(settings.getMaxConcurrentDownloads());
        boolean dark = resolveDark();
        Application.setUserAgentStylesheet(
                dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
        // Lagoon & Shore rides on the style class: looked-up colors resolve per node, so flipping
        // one class on the root recolors everything reading the tokens.
        if (root.getScene() != null) {
            var classes = root.getScene().getRoot().getStyleClass();
            if (dark && !classes.contains("insula-dark")) {
                classes.add("insula-dark");
            } else if (!dark) {
                classes.remove("insula-dark");
            }
        }
        renderer.setZoom(settings.getZoomPercent() / 100.0);
        applyReaderTheme();
        transports.setTorrentEnabled(settings.isTorrentEnabled());
        transports.setTorrentThreshold(settings.getTorrentThresholdBytes());
        applyTranscodeSupport();
        if (settingsDialog != null) {
            settingsDialog.sync();
        }
    }

    /**
     * The effective brightness. "System" follows the OS through JavaFX's platform preferences,
     * and a listener re-applies on the fly, so an OS-scheduled sunset switch reaches Insula
     * without a restart.
     */
    private boolean resolveDark() {
        if (!settings.isSystemTheme()) {
            return settings.isDark();
        }
        try {
            return Platform.getPreferences().getColorScheme() == javafx.application.ColorScheme.DARK;
        } catch (RuntimeException e) {
            return false; // a platform without the preference reads as light
        }
    }

    private boolean systemThemeListenerInstalled;

    private void watchSystemTheme() {
        if (systemThemeListenerInstalled) {
            return;
        }
        systemThemeListenerInstalled = true;
        try {
            Platform.getPreferences().colorSchemeProperty().addListener((obs, old, now) -> {
                if (settings.isSystemTheme()) {
                    applySettings();
                }
            });
        } catch (RuntimeException e) {
            // no preference support on this platform; "system" simply behaves as light
        }
    }

    private void saveAndApply() {
        settings.save();
        applySettings();
    }

    private void showSettings() {
        if (settingsDialog == null) {
            settingsDialog = new SettingsDialog(stage, settings, this::applySettings);
            settingsDialog.setArchivesFolder(archivesDir(), this::revealArchivesFolder, this::changeArchivesFolder);
            settingsDialog.setConfigFolder(dataDir, () -> {
                if (hostServices != null) {
                    hostServices.showDocument(dataDir.toUri().toString());
                }
            });
            settingsDialog.setCatalogInfo(this::catalogAgeLabel, () -> commands.run("catalog.refresh"));
        }
        settingsDialog.show();
    }

    /** "Last refreshed 3 days ago · stale past 14" — the Catalog settings page's one fact. */
    private String catalogAgeLabel() {
        long fetched = catalogCache.fetchedAtMillis();
        if (fetched <= 0) {
            return "Never refreshed — the Catalog will offer to fetch it";
        }
        long days = java.time.Duration.ofMillis(System.currentTimeMillis() - fetched)
                .toDays();
        String age = days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
        return "Last refreshed " + age
                + (days > com.insula.catalog.CatalogCache.STALE_AGE.toDays() ? " — stale, worth refreshing" : "");
    }

    /**
     * Registers an existing .zim on the shelf without moving it.
     *
     * <p>Deliberately not a copy: an archive is routinely tens of gigabytes and often lives on the
     * external drive it was handed over on, so copying it into the archives folder inside a modal
     * would be both slow and presumptuous. The entry points at the file where it is, and is marked
     * unverified — there is no publisher checksum for a file that did not come from the catalog,
     * and claiming otherwise would devalue the tick on everything else.
     */
    private void importArchive() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Import archive into library");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIM archives", "*.zim"));
        java.io.File chosen = chooser.showOpenDialog(stage);
        if (chosen == null) {
            return;
        }
        Path file = chosen.toPath().toAbsolutePath();
        if (library.entries().stream().anyMatch(e -> e.file().equals(file))) {
            status.setText(file.getFileName() + " is already in your library");
            openZim(file);
            return;
        }
        try (com.insula.zim.ZimArchive probe = com.insula.zim.ZimArchive.open(file)) {
            String title = probe.metadata("Title").orElse(file.getFileName().toString());
            library.put(new com.insula.library.LibraryEntry(
                    file, title, Files.size(file), "", false, System.currentTimeMillis()));
            library.save();
            status.setText("Added " + title + " to your library — left where it is, and not verified");
            openZim(file);
            if (surface == Surface.LIBRARY) {
                libraryPane.activate();
            }
        } catch (IOException e) {
            status.setText("Could not read " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * The starter picks on demand. They are hidden once the library has anything in it — a
     * stocked library must not nag — so this is the way back to them.
     */
    private void showStarterPicks() {
        List<StarterPicks.Resolved> picks = StarterPicks.resolve(
                com.insula.catalog.CatalogGroups.group(catalogCache.entries()), freeDiskBytes(dataDir));
        if (picks.isEmpty()) {
            status.setText("No starter picks — refresh the catalog first");
            showCatalog();
            return;
        }
        VBox box = new VBox(10);
        for (StarterPicks.Resolved pick : picks) {
            Label title = new Label(pick.pick().label() + " — " + pick.group().title());
            title.getStyleClass().add("card-title");
            Label blurb = new Label(pick.pick().blurb());
            blurb.getStyleClass().add("card-sub");
            blurb.setWrapText(true);
            VBox main = new VBox(2, title, blurb);
            HBox.setHgrow(main, Priority.ALWAYS);
            Button get = new Button("Download · " + Formats.bytes(pick.entry().sizeBytes()));
            get.setOnAction(e -> {
                get.setDisable(true);
                downloadUpdate(pick.entry());
            });
            HBox row = new HBox(12, main, get);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("rowcard");
            box.getChildren().add(row);
        }
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("Starter picks");
        dialog.setHeaderText("A few good places to start.");
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.getDialogPane()
                .getStylesheets()
                .add(getClass().getResource("insula.css").toExternalForm());
        dialog.showAndWait();
    }

    /** The kit's Help entry: what the thing on your disk actually is. */
    private void showZimExplainer() {
        javafx.scene.control.Alert alert =
                new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle("What's in a ZIM file?");
        alert.setHeaderText("One file, one whole library.");
        alert.setContentText("""
                A .zim archive is a compressed, indexed snapshot of a website — every article, \
                image and stylesheet in a single file, with a directory so any page can be found \
                without unpacking the rest.

                It is an open format (openzim.org), the same one Kiwix uses, so an archive you \
                download here opens in any ZIM reader on any platform. Nothing about it is \
                specific to Insula.

                That is what makes them worth the disk: they are ordinary files. Copy one to a \
                USB stick, hand it to someone with no connection at all, and it opens exactly as \
                it does here — no account, no server, no network.""");
        alert.getDialogPane().setMinWidth(520);
        alert.showAndWait();
    }

    /**
     * The two menu lists that change while the app runs. Read when the menu opens, so there is
     * nothing to keep in sync.
     */
    /**
     * Archives still being uploaded over BitTorrent.
     *
     * <p>Guarded like every other torrent read: merely touching {@link TorrentTransport} throws
     * when the native library is absent, and the Library has to render on a machine without it.
     */
    private List<TorrentTransport.Seed> activeSeeds() {
        if (!settings.isTorrentEnabled()) {
            return List.of();
        }
        try {
            return TorrentTransport.activeSeeds();
        } catch (Throwable t) {
            return List.of();
        }
    }

    private Menus.Dynamic menuDynamics() {
        return new Menus.Dynamic() {
            @Override
            public List<Menus.Entry> recentArchives() {
                return settings.getRecentArchives().stream()
                        .map(Path::of)
                        // A deleted or unplugged archive is dropped rather than offered: a recent
                        // entry that only ever reports failure is worse than a shorter list.
                        .filter(Files::isRegularFile)
                        .map(p -> new Menus.Entry(Formats.collapseHome(p.toString()), () -> openZim(p)))
                        .toList();
            }

            @Override
            public List<Menus.Entry> bookmarks() {
                return bookmarks.entries().stream()
                        .limit(MENU_BOOKMARKS)
                        .map(ref -> new Menus.Entry(ref.title() + "   " + ref.bookTitle(), () -> openRef(ref, false)))
                        .toList();
            }
        };
    }

    /** Enough to be a shortcut, few enough that the full panel still has a reason to exist. */
    private static final int MENU_BOOKMARKS = 8;

    /**
     * Puts the sidebar where the settings say — or removes it entirely.
     *
     * <p>Rebuilt rather than hidden: a {@code SplitPane} keeps a divider for an invisible item, so
     * hiding the node leaves a dead strip and a draggable divider that moves nothing.
     */
    private void applySidebarLayout() {
        if (readerSplit == null || readerPane == null) {
            return;
        }
        boolean right = settings.isSidebarOnRight();
        if (!settings.isSidebarVisible()) {
            readerSplit.getItems().setAll(readerPane);
            return;
        }
        readerSplit.getItems().setAll(right ? List.of(readerPane, sidebarHost) : List.of(sidebarHost, readerPane));
        // The divider is a fraction of the whole, so the sidebar's share is mirrored when it
        // changes sides — otherwise moving it right leaves a quarter-width article.
        readerSplit.setDividerPositions(right ? 1 - SIDEBAR_FRACTION : SIDEBAR_FRACTION);
    }

    private static final double SIDEBAR_FRACTION = 0.24;

    private void toggleSidebar() {
        settings.setSidebarVisible(!settings.isSidebarVisible());
        settings.save();
        applySidebarLayout();
        status.setText(settings.isSidebarVisible() ? "Sidebar shown" : "Sidebar hidden");
    }

    private void toggleSidebarSide() {
        settings.setSidebarOnRight(!settings.isSidebarOnRight());
        // Moving a hidden sidebar would be a silent no-op, so the move reveals it.
        settings.setSidebarVisible(true);
        settings.save();
        applySidebarLayout();
        status.setText("Sidebar moved to the " + (settings.isSidebarOnRight() ? "right" : "left"));
    }

    /** A message too long for the status bar, which is where a list of file names belongs least. */
    private void longError(String header, String detail) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("Archives folder");
        alert.setHeaderText(header);
        javafx.scene.control.TextArea body = new javafx.scene.control.TextArea(detail);
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(8);
        alert.getDialogPane().setContent(body);
        alert.getDialogPane().setMinWidth(560);
        alert.showAndWait();
    }

    /** Where archives live by default: beside the rest of the config. */
    private Path defaultArchivesDir() {
        return dataDir.resolve("archives");
    }

    /** Where archives live now. */
    Path archivesDir() {
        return settings.getArchivesFolder(defaultArchivesDir());
    }

    /**
     * Changes the archives folder, offering to bring the existing archives along.
     *
     * <p>Both answers are legitimate and neither is a trick: the library index holds absolute
     * paths and already spans folders — "Import into library" registers files where they sit — so
     * leaving them behind keeps every archive working exactly as before. Moving is what most
     * people mean, so it is the default button, but it is spelled out in gigabytes first.
     */
    private void changeArchivesFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Choose a folder for archives");
        Path current = archivesDir();
        if (Files.isDirectory(current)) {
            chooser.setInitialDirectory(current.toFile());
        }
        java.io.File chosen = chooser.showDialog(stage);
        if (chosen == null) {
            return;
        }
        Path target = chosen.toPath().toAbsolutePath().normalize();
        if (target.equals(current.toAbsolutePath().normalize())) {
            return;
        }
        if (!Files.isDirectory(target) || !Files.isWritable(target)) {
            status.setText("Cannot use " + target + " — it is not a writable folder");
            return;
        }

        ArchiveMove.Plan plan = ArchiveMove.plan(library.entries(), current, target, Files::exists);
        if (!plan.runnable()) {
            longError(
                    "Some archives already exist there",
                    "These names are already in " + target + ":\n\n" + String.join("\n", plan.conflicts())
                            + "\n\nMove or rename them first — replacing them would destroy files "
                            + "this app did not put there.");
            return;
        }
        if (plan.isEmpty()) {
            applyArchivesFolder(target);
            status.setText("New downloads will go to " + target);
            return;
        }

        javafx.scene.control.ButtonType move =
                new javafx.scene.control.ButtonType("Move them", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType leave = new javafx.scene.control.ButtonType(
                "Leave them where they are", javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.Alert ask = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "",
                move,
                leave,
                javafx.scene.control.ButtonType.CANCEL);
        ask.initOwner(stage);
        ask.setTitle("Change archives folder");
        ask.setHeaderText("Move your existing archives to the new folder?");
        ask.setContentText(plan.steps().size() + " archives · " + Formats.bytes(plan.bytes())
                + "\n\nMoving can take a while for large archives. Leaving them is safe: they keep working "
                + "from where they are, and only new downloads go to the new folder."
                + (plan.staying() > 0
                        ? "\n\n" + plan.staying() + " archive(s) outside the old folder stay put either way."
                        : ""));
        ask.getDialogPane().setMinWidth(520);
        javafx.scene.control.ButtonType answer = ask.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        if (answer == javafx.scene.control.ButtonType.CANCEL) {
            return;
        }
        applyArchivesFolder(target);
        if (answer == move) {
            moveArchives(plan);
        } else {
            status.setText("New downloads will go to " + target + "; existing archives stayed put");
        }
    }

    private void applyArchivesFolder(Path folder) {
        settings.setArchivesFolder(folder);
        settings.save();
        downloads.setDownloadDir(folder);
        if (settingsDialog != null) {
            settingsDialog.setArchivesFolder(folder, this::revealArchivesFolder, this::changeArchivesFolder);
            settingsDialog.sync();
        }
    }

    /**
     * Moves the planned files off the FX thread, updating the index one file at a time.
     *
     * <p>Per file, not at the end: a move interrupted halfway must still leave every library entry
     * pointing at a file that exists. Whatever has moved is recorded as moved; whatever has not
     * still points where it still is.
     */
    private void moveArchives(ArchiveMove.Plan plan) {
        status.setText("Moving " + plan.steps().size() + " archives…");
        Thread mover = new Thread(
                () -> {
                    int moved = 0;
                    List<String> failed = new java.util.ArrayList<>();
                    for (ArchiveMove.Step step : plan.steps()) {
                        try {
                            Files.createDirectories(step.to().getParent());
                            Files.move(step.from(), step.to());
                            LibraryEntry relocated = step.entry().withFile(step.to());
                            Platform.runLater(() -> {
                                library.relocate(step.from(), relocated);
                                library.save();
                            });
                            moved++;
                        } catch (IOException e) {
                            failed.add(step.entry().fileName() + ": " + e.getMessage());
                        }
                    }
                    int done = moved;
                    Platform.runLater(() -> {
                        libraryPane.activate();
                        if (failed.isEmpty()) {
                            status.setText("Moved " + done + " archives");
                        } else {
                            longError(
                                    "Some archives could not be moved",
                                    "Moved " + done + ". These stayed where they were and still work:\n\n"
                                            + String.join("\n", failed));
                        }
                    });
                },
                "archive-move");
        mover.setDaemon(true);
        mover.start();
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
    /**
     * Reopens the previous sitting: every tab, with the last-active one selected.
     *
     * <p>Falls back to the single last archive when there is no session to restore, so an install
     * that predates sessions — or one where every archive has since been deleted — still behaves
     * the way it used to. Tabs whose archive is gone are dropped and <em>counted</em>, because
     * silently reopening four of five tabs looks like a bug from the outside.
     */
    void openLastArchiveIfEnabled() {
        if (!settings.isReopenLastArchive()) {
            return;
        }
        com.insula.reader.ReaderSession.Restored restored = session.restore(Files::isRegularFile);
        if (!restored.open().isEmpty()) {
            restoreTabs(restored);
            return;
        }
        String last = settings.getLastArchive();
        if (!last.isBlank() && Files.isRegularFile(Path.of(last))) {
            openZim(Path.of(last));
        }
    }

    private void restoreTabs(com.insula.reader.ReaderSession.Restored restored) {
        // Opened in order so the strip matches last time, then the remembered one is selected —
        // opening the active tab last would reorder the strip instead.
        for (ArticleRef ref : restored.open()) {
            openRef(ref, true);
        }
        int active = restored.activeIndex();
        if (active >= 0 && active < tabs.count()) {
            ReaderTabs.Tab tab = tabs.tabs().get(active);
            tabs.selectTab(tab);
            syncTabBar();
            activateTab(tab);
        }
        if (restored.dropped() > 0) {
            status.setText("Reopened " + restored.open().size() + " tab"
                    + (restored.open().size() == 1 ? "" : "s") + " · " + restored.dropped()
                    + " skipped, their archives are no longer on this device");
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
            results.getItems().clear();
            searchField.clear();
            currentArchiveFile = file.toAbsolutePath();
            lastArticlePath = null;
            settings.setLastArchive(currentArchiveFile.toString());
            settings.setRecentArchives(
                    com.insula.config.RecentList.promote(settings.getRecentArchives(), currentArchiveFile.toString()));
            settings.save();
            // The archive being read participates in search alongside the rest of the library.
            librarySearch.add(currentArchiveFile, bookTitle, archive);
            syncNavTabs();
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
            refreshStatusBar();
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
                if (saved >= RESTORE_NOTICE_FLOOR) {
                    showRestoredBar(saved);
                } else {
                    hideRestoredBar();
                }
            } else {
                hideRestoredBar();
            }
        } else {
            hideRestoredBar();
        }
    }

    /**
     * The "you were N% through — restored" line.
     *
     * <p>Its value is not the announcement but the escape hatch beside it: being dropped into the
     * middle of an article with no explanation and no way back to the top is disorienting, and a
     * message without the button would only name the confusion rather than resolve it. It is shown
     * only past {@link #RESTORE_NOTICE_FLOOR}, because restoring 2% is indistinguishable from the
     * top and would make the bar appear for essentially every article.
     */
    private void buildRestoredBar() {
        restoredLabel.getStyleClass().add("restored-text");
        // A link, not a button. The kit shows no action at all here; scrolling up is the obvious
        // recovery. But Ctrl+Home is not obvious inside a WebView, and a link costs the line
        // nothing, so this is a deliberate one-word addition to an otherwise verbatim treatment.
        javafx.scene.control.Hyperlink toTop = new javafx.scene.control.Hyperlink("Back to top");
        toTop.setOnAction(e -> {
            renderer.scrollTo(0);
            hideRestoredBar();
        });
        restoredBar.getChildren().addAll(restoredLabel, toTop);
        restoredBar.setAlignment(Pos.CENTER_LEFT);
        restoredBar.getStyleClass().add("restored-notice");
        hideRestoredBar();
    }

    /** Below this a restore is indistinguishable from the top, so saying so is noise. */
    static final double RESTORE_NOTICE_FLOOR = 0.05;

    /** The kit's wording, in the kit's place: quiet, in the header, never a toast over the text. */
    private void showRestoredBar(double fraction) {
        String where = bookTitle == null || bookTitle.isBlank() ? "" : bookTitle + " · ";
        restoredLabel.setText(where + "you were " + Math.round(fraction * 100) + "% through — restored");
        restoredBar.setVisible(true);
        restoredBar.setManaged(true);
    }

    private void hideRestoredBar() {
        restoredBar.setVisible(false);
        restoredBar.setManaged(false);
    }

    String restoredNoticeForTest() {
        return restoredBar.isVisible() ? restoredLabel.getText() : "";
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
            rememberSession();
        } catch (RuntimeException e) {
            // a failed session save must never block shutdown
        }
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
