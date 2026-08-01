package com.offlinewiki.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.offlinewiki.catalog.CatalogClient;
import com.offlinewiki.catalog.ZimEntry;
import com.offlinewiki.download.DownloadManager;
import com.offlinewiki.download.DownloadState;
import com.offlinewiki.download.ProgressSnapshot;
import com.offlinewiki.library.Library;
import com.offlinewiki.library.LibraryEntry;

/**
 * Browse the online catalog, download archives, and open the ones already on disk.
 *
 * <p>Deliberately card-shaped rather than Kiwix's tree-with-checkboxes: each row carries the
 * title, a description, and the facts that decide whether to spend the bandwidth (size, article
 * count, language).
 *
 * <p><b>Progress is sampled, not pushed.</b> Transports emit progress far faster than a UI can
 * paint, so a {@code Platform.runLater} per event floods the FX queue and the window stutters.
 * A single {@link Timeline} polls every {@value #REFRESH_MILLIS} ms (~4 Hz) and repaints only the
 * rows that changed.
 */
final class LibraryPane {

    static final int REFRESH_MILLIS = 250;
    private static final int CATALOG_PAGE = 40;

    private final CatalogClient catalog;
    private final DownloadManager downloads;
    private final Library library;
    private final Consumer<Path> onOpenArchive;
    private final Consumer<String> onStatus;

    private final BorderPane root = new BorderPane();
    private final TextField searchField = new TextField();
    private final ListView<ZimEntry> catalogList = new ListView<>();
    private final ListView<Object> localList = new ListView<>();
    private final Label catalogStatus = new Label("");

    private final Timeline refresh =
            new Timeline(new KeyFrame(Duration.millis(REFRESH_MILLIS), e -> refreshDownloadRows()));

    LibraryPane(
            CatalogClient catalog,
            DownloadManager downloads,
            Library library,
            Consumer<Path> onOpenArchive,
            Consumer<String> onStatus) {
        this.catalog = catalog;
        this.downloads = downloads;
        this.library = library;
        this.onOpenArchive = onOpenArchive;
        this.onStatus = onStatus;
        build();
        refresh.setCycleCount(Animation.INDEFINITE);
    }

    Region node() {
        return root;
    }

    /** Starts the 4 Hz sampler. Called when the pane becomes visible. */
    void activate() {
        refreshLocal();
        if (refresh.getStatus() != Animation.Status.RUNNING) {
            refresh.play();
        }
    }

    /** Stops sampling — an invisible pane must not keep waking the FX thread. */
    void deactivate() {
        refresh.stop();
    }

    void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    private void build() {
        searchField.setPromptText("Search the Kiwix catalog (Wikipedia, Wiktionary, StackExchange…)");
        searchField.setOnAction(e -> runSearch());
        Button searchButton = new Button("Search");
        searchButton.setOnAction(e -> runSearch());
        HBox searchRow = new HBox(8, searchField, searchButton);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchRow.setPadding(new Insets(8));

        catalogList.setCellFactory(v -> new CatalogCell());
        catalogList.setPlaceholder(new Label("Search the catalog to find archives to download"));
        VBox.setVgrow(catalogList, Priority.ALWAYS);
        catalogStatus.setPadding(new Insets(4, 10, 6, 10));
        VBox catalogSide = new VBox(searchRow, catalogList, catalogStatus);

        localList.setCellFactory(v -> new LocalCell());
        localList.setPlaceholder(new Label("Nothing downloaded yet"));
        VBox.setVgrow(localList, Priority.ALWAYS);
        Label localHeading = new Label("On this computer");
        localHeading.setPadding(new Insets(10, 10, 6, 10));
        localHeading.setStyle("-fx-font-weight: bold;");
        VBox localSide = new VBox(localHeading, localList);

        SplitPane split = new SplitPane(catalogSide, localSide);
        split.setDividerPositions(0.58);
        root.setCenter(split);
    }

    private void runSearch() {
        String query = searchField.getText();
        catalogStatus.setText("Searching…");
        catalog.search(
                query,
                "",
                CATALOG_PAGE,
                outcome -> Platform.runLater(() -> {
                    if (!outcome.ok()) {
                        catalogStatus.setText(outcome.error());
                        onStatus.accept(outcome.error());
                        return;
                    }
                    List<ZimEntry> entries = outcome.feed().entries();
                    catalogList.getItems().setAll(entries);
                    catalogStatus.setText(
                            entries.isEmpty()
                                    ? "No archives matched"
                                    : entries.size() + " shown"
                                            + (outcome.feed().totalResults() > entries.size()
                                                    ? " of " + outcome.feed().totalResults()
                                                    : ""));
                }));
    }

    private void download(ZimEntry entry) {
        downloads.enqueue(entry);
        onStatus.accept("Downloading " + entry.displayName());
        refreshLocal();
        activate();
    }

    /** Rebuilds the local list: active downloads first, then archives already on disk. */
    private void refreshLocal() {
        List<Object> rows = new java.util.ArrayList<>();
        rows.addAll(downloads.jobs());
        library.entries().forEach(entry -> {
            boolean downloading =
                    downloads.jobs().stream().anyMatch(j -> j.destination().equals(entry.file()));
            if (!downloading) {
                rows.add(entry);
            }
        });
        localList.getItems().setAll(rows);
    }

    /**
     * The 4 Hz tick. Only touches cells whose snapshot changed, and stops the timer once every
     * download is terminal so an idle window does no per-frame work.
     */
    private void refreshDownloadRows() {
        List<DownloadManager.Job> jobs = downloads.jobs();
        if (jobs.isEmpty()) {
            refresh.stop();
            return;
        }
        boolean anyActive = false;
        for (DownloadManager.Job job : jobs) {
            if (!job.snapshot().state().isTerminal()) {
                anyActive = true;
                break;
            }
        }
        // Cells re-render from the live snapshot; a refresh() is the cheapest correct nudge here
        // because the row identity has not changed, only its contents.
        localList.refresh();
        if (!anyActive) {
            refresh.stop();
            refreshLocal();
        }
    }

    /** A catalog row: title, summary, and the facts that justify the download. */
    private final class CatalogCell extends ListCell<ZimEntry> {
        @Override
        protected void updateItem(ZimEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                return;
            }
            Label title = new Label(entry.displayName());
            title.setStyle("-fx-font-weight: bold;");
            Label summary = new Label(entry.summary());
            summary.setWrapText(true);
            summary.setStyle("-fx-opacity: 0.75;");

            String facts = Formats.bytes(entry.sizeBytes())
                    + (entry.articleCount() > 0 ? " · " + entry.articleCount() + " articles" : "")
                    + (entry.languages().isEmpty() ? "" : " · " + String.join(", ", entry.languages()));
            Label meta = new Label(facts);
            meta.setStyle("-fx-opacity: 0.6;");

            Button get = new Button("Download");
            get.setOnAction(e -> download(entry));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox bottom = new HBox(8, meta, spacer, get);
            bottom.setAlignment(Pos.CENTER_LEFT);

            VBox box = new VBox(4, title, summary, bottom);
            box.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(box);
        }
    }

    /** A local row: either an in-flight download or an archive on disk. */
    private final class LocalCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            setGraphic(item instanceof DownloadManager.Job job ? downloadRow(job) : archiveRow((LibraryEntry) item));
        }

        private Region downloadRow(DownloadManager.Job job) {
            ProgressSnapshot snapshot = job.snapshot();
            Label title = new Label(job.entry().displayName());
            title.setStyle("-fx-font-weight: bold;");

            ProgressBar bar = new ProgressBar();
            double fraction = snapshot.fraction();
            bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
            bar.setMaxWidth(Double.MAX_VALUE);

            Label detail = new Label(Formats.progressLine(snapshot));
            detail.setStyle("-fx-opacity: 0.7;");

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_LEFT);
            if (snapshot.state() == DownloadState.COMPLETED) {
                Button open = new Button("Open");
                open.setOnAction(e -> onOpenArchive.accept(job.destination()));
                actions.getChildren().add(open);
            } else if (!snapshot.state().isTerminal()) {
                Button cancel = new Button("Cancel");
                cancel.setOnAction(e -> {
                    job.cancel();
                    onStatus.accept("Cancelled " + job.entry().displayName());
                });
                actions.getChildren().add(cancel);
            }

            VBox box = new VBox(4, title, bar, detail);
            if (!actions.getChildren().isEmpty()) {
                box.getChildren().add(actions);
            }
            box.setPadding(new Insets(8, 4, 8, 4));
            return box;
        }

        private Region archiveRow(LibraryEntry entry) {
            Label title = new Label(entry.title());
            title.setStyle("-fx-font-weight: bold;");
            Label meta = new Label(Formats.bytes(entry.sizeBytes())
                    + (entry.verified() ? " · verified" : " · not verified")
                    + " · " + entry.fileName());
            meta.setStyle("-fx-opacity: 0.6;");

            Button open = new Button("Open");
            open.setOnAction(e -> onOpenArchive.accept(entry.file()));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox bottom = new HBox(8, meta, spacer, open);
            bottom.setAlignment(Pos.CENTER_LEFT);

            VBox box = new VBox(4, title, bottom);
            box.setPadding(new Insets(8, 4, 8, 4));
            return box;
        }
    }
}
