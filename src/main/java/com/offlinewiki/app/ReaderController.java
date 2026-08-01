package com.offlinewiki.app;

import java.io.IOException;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.offlinewiki.server.ZimHttpServer;
import com.offlinewiki.zim.Dirent;
import com.offlinewiki.zim.ZimArchive;

/**
 * The reader shell: toolbar (open / back / forward / home / theme), a search-as-you-type
 * sidebar over the archive's title index, and a WebView reading pane fed by the loopback
 * {@link ZimHttpServer}. Keyboard-first: Ctrl+L jumps to search, Alt+Left/Right navigate.
 */
final class ReaderController {

    private static final int SEARCH_LIMIT = 40;

    private final Stage stage;
    private final HostServices hostServices;

    private final BorderPane root = new BorderPane();
    private final TextField searchField = new TextField();
    private final ListView<ZimArchive.SearchResult> results = new ListView<>();
    private final WebView webView = new WebView();
    private final Button backButton = new Button("←");
    private final Button forwardButton = new Button("→");
    private final Button homeButton = new Button("Home");
    private final Label status = new Label("Open a .zim archive to start reading");

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

    ReaderController(Stage stage, HostServices hostServices) {
        this.stage = stage;
        this.hostServices = hostServices;
        buildUi();
    }

    Parent root() {
        return root;
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        Button openButton = new Button("Open…");
        openButton.setOnAction(e -> chooseAndOpen());
        backButton.setOnAction(e -> historyGo(-1));
        forwardButton.setOnAction(e -> historyGo(1));
        homeButton.setOnAction(e -> goHome());
        backButton.setDisable(true);
        forwardButton.setDisable(true);
        homeButton.setDisable(true);

        ToggleButton darkToggle = new ToggleButton("Dark");
        darkToggle.setOnAction(e -> Application.setUserAgentStylesheet(
                darkToggle.isSelected()
                        ? new PrimerDark().getUserAgentStylesheet()
                        : new PrimerLight().getUserAgentStylesheet()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolbar =
                new ToolBar(openButton, new Separator(), backButton, forwardButton, homeButton, spacer, darkToggle);

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
            if (e.getClickCount() >= 1) {
                ZimArchive.SearchResult selected = results.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    openResult(selected);
                }
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

        SplitPane split = new SplitPane(sidebar, webView);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);

        WebEngine engine = webView.getEngine();
        engine.locationProperty().addListener((obs, old, location) -> onLocationChanged(location));
        engine.getHistory().currentIndexProperty().addListener((obs, old, idx) -> updateNavButtons());
        engine.getHistory().getEntries().addListener((javafx.collections.ListChangeListener<WebHistory.Entry>)
                c -> updateNavButtons());

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(4, 10, 4, 10));

        root.setTop(toolbar);
        root.setCenter(split);
        root.setBottom(statusBar);
    }

    void installShortcuts(Scene scene) {
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), () -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
        scene.getAccelerators()
                .put(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), this::chooseAndOpen);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN), () -> historyGo(-1));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN), () -> historyGo(1));
    }

    // ---------------------------------------------------------------- archive lifecycle

    private void chooseAndOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open ZIM archive");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIM archives", "*.zim"));
        java.io.File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            openZim(chosen.toPath());
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
        searchExecutor.execute(() -> {
            try {
                List<ZimArchive.SearchResult> found = current.searchByTitle(query.strip(), SEARCH_LIMIT);
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
        closeArchive();
        if (server != null) {
            server.close();
        }
    }
}
