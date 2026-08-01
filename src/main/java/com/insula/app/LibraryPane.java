package com.insula.app;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import com.insula.download.DownloadManager;
import com.insula.library.Library;
import com.insula.library.LibraryEntry;

/**
 * Home. Everything on this screen answers one of three questions: what's arriving, what can I
 * read, what needs attention?
 *
 * <p>Layout per the design spec: a disk gauge as the header (the number that matters — free
 * space — written out), a pinned "Arriving" section while downloads run (downloads are not a
 * place; there is no separate tab to hunt for), and the archive rows.
 *
 * <p>Progress is sampled at 4 Hz into per-job {@link DownloadRow}s updated <b>in place</b>; the
 * row set itself is rebuilt only when jobs appear or vanish. The timer stops when the pane is
 * hidden or nothing is in flight.
 */
final class LibraryPane {

    /** Disk facts for the gauge. */
    record DiskInfo(long usedByArchives, int archiveCount, long freeBytes, long totalBytes) {}

    static final int REFRESH_MILLIS = 250;

    private final DownloadManager downloads;
    private final Library library;
    private final Consumer<Path> onOpenArchive;
    private final Consumer<String> onStatus;
    private final Supplier<DiskInfo> diskInfo;

    private final ScrollPane root;
    private final VBox content = new VBox(12);
    private final HBox gauge = new HBox(14);
    private final ProgressBar gaugeBar = new ProgressBar(0);
    private final Label gaugeLabel = new Label();
    private final VBox arrivingSection = new VBox(8);
    private final VBox arrivingRows = new VBox(8);
    private final VBox attentionSection = new VBox(8);
    private final VBox attentionRows = new VBox(8);
    private final VBox deviceRows = new VBox(8);

    private final Map<DownloadManager.Job, DownloadRow> rows = new LinkedHashMap<>();
    private Map<String, com.insula.catalog.ZimEntry> updates = Map.of();
    private Consumer<com.insula.catalog.ZimEntry> onUpdate = e -> {};
    private int updatePillCount;
    private List<com.insula.catalog.StarterPicks.Resolved> starters = List.of();
    private Consumer<com.insula.catalog.ZimEntry> onDownloadStarter = e -> {};
    private Runnable onOpenStore = () -> {};
    private List<Path> quarantined = List.of();
    private Consumer<Path> onRepair = p -> {};
    private Consumer<Path> onDeleteQuarantined = p -> {};
    private final Timeline sampler = new Timeline(new KeyFrame(Duration.millis(REFRESH_MILLIS), e -> tick()));

    LibraryPane(
            DownloadManager downloads,
            Library library,
            Consumer<Path> onOpenArchive,
            Consumer<String> onStatus,
            Supplier<DiskInfo> diskInfo) {
        this.downloads = downloads;
        this.library = library;
        this.onOpenArchive = onOpenArchive;
        this.onStatus = onStatus;
        this.diskInfo = diskInfo;
        sampler.setCycleCount(Animation.INDEFINITE);

        gaugeBar.setPrefWidth(280);
        Label heading = new Label("Archives");
        heading.setStyle("-fx-font-weight: bold;");
        gauge.getChildren().addAll(heading, gaugeBar, gaugeLabel);
        gauge.setAlignment(Pos.CENTER_LEFT);
        gauge.setPadding(new Insets(12));
        gauge.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        gaugeLabel.setStyle("-fx-opacity: 0.7;");

        arrivingSection.getChildren().addAll(sectionTitle("Arriving"), arrivingRows);
        attentionSection.getChildren().addAll(sectionTitle("Needs attention"), attentionRows);

        content.setPadding(new Insets(14));
        content.getChildren()
                .addAll(gauge, arrivingSection, attentionSection, sectionTitle("On this device"), deviceRows);

        root = new ScrollPane(content);
        root.setFitToWidth(true);
    }

    Region node() {
        return root;
    }

    void activate() {
        rebuildAll();
        if (sampler.getStatus() != Animation.Status.RUNNING) {
            sampler.play();
        }
    }

    void deactivate() {
        sampler.stop();
    }

    /**
     * Newer catalog builds keyed by the installed file name they replace. Rows re-render with an
     * Update pill; clicking one hands the replacement entry back for download.
     */
    void setUpdates(Map<String, com.insula.catalog.ZimEntry> updates, Consumer<com.insula.catalog.ZimEntry> onUpdate) {
        this.updates = Map.copyOf(updates);
        this.onUpdate = onUpdate;
        rebuildDevice();
    }

    /** Quarantined files offered for piece-level Repair (or deletion). */
    void setAttention(List<Path> quarantined, Consumer<Path> onRepair, Consumer<Path> onDeleteQuarantined) {
        this.quarantined = List.copyOf(quarantined);
        this.onRepair = onRepair;
        this.onDeleteQuarantined = onDeleteQuarantined;
        rebuildAttention();
    }

    /** First-run suggestions shown while the library is empty; resolved names, never links. */
    void setStarters(
            List<com.insula.catalog.StarterPicks.Resolved> starters,
            Consumer<com.insula.catalog.ZimEntry> onDownloadStarter,
            Runnable onOpenStore) {
        this.starters = List.copyOf(starters);
        this.onDownloadStarter = onDownloadStarter;
        this.onOpenStore = onOpenStore;
        rebuildDevice();
    }

    // ---------------------------------------------------------------- sampling

    private void tick() {
        List<DownloadManager.Job> jobs = downloads.jobs();
        boolean setChanged = jobs.size() != rows.size() || !rows.keySet().containsAll(jobs);
        if (setChanged) {
            rebuildArriving(jobs);
            rebuildDevice(); // a finished download admits an archive; reflect it promptly
        } else {
            rows.values().forEach(DownloadRow::update);
        }
        boolean anyActive = jobs.stream().anyMatch(j -> !j.snapshot().state().isTerminal());
        if (!anyActive) {
            rows.values().forEach(DownloadRow::update);
            rebuildDevice();
            sampler.stop();
        }
    }

    private void rebuildAll() {
        rebuildArriving(downloads.jobs());
        rebuildAttention();
        rebuildDevice();
    }

    private void rebuildAttention() {
        attentionRows.getChildren().clear();
        for (Path file : quarantined) {
            attentionRows.getChildren().add(attentionRow(file));
        }
        boolean visible = !quarantined.isEmpty();
        attentionSection.setVisible(visible);
        attentionSection.setManaged(visible);
    }

    /** A quarantined file: what it is, why it's here, and the two ways out. */
    private Region attentionRow(Path file) {
        Label title = new Label(file.getFileName().toString());
        title.setStyle("-fx-font-weight: bold;");
        Label meta = new Label("Failed checksum verification — repair re-downloads only the damaged parts");
        meta.setStyle("-fx-opacity: 0.55; -fx-font-size: 0.85em;");
        VBox main = new VBox(2, title, meta);
        HBox.setHgrow(main, Priority.ALWAYS);

        Button repair = new Button("Repair");
        repair.setOnAction(e -> {
            repair.setDisable(true);
            onRepair.accept(file);
        });
        Button delete = new Button("Delete");
        delete.setOnAction(e -> onDeleteQuarantined.accept(file));

        HBox row = new HBox(12, main, repair, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: #a16207;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        return row;
    }

    private void rebuildArriving(List<DownloadManager.Job> jobs) {
        rows.keySet().retainAll(jobs);
        for (DownloadManager.Job job : jobs) {
            rows.computeIfAbsent(job, j -> new DownloadRow(j, j.entry().displayName(), onOpenArchive, onStatus));
        }
        arrivingRows.getChildren().setAll(rows.values());
        boolean visible = !jobs.isEmpty();
        arrivingSection.setVisible(visible);
        arrivingSection.setManaged(visible);
    }

    private void rebuildDevice() {
        deviceRows.getChildren().clear();
        updatePillCount = 0;
        List<LibraryEntry> entries = library.entries();
        if (entries.isEmpty()) {
            deviceRows.getChildren().add(emptyState());
        }
        for (LibraryEntry entry : entries) {
            deviceRows.getChildren().add(archiveRow(entry));
        }
        updateGauge();
    }

    /**
     * The first-run screen: a sentence, the starter picks (when the cached catalog can resolve
     * them), and a Store call-to-action either way. Nothing here may block or hit the network.
     */
    private Region emptyState() {
        VBox box = new VBox(10);
        Label empty = new Label(
                starters.isEmpty()
                        ? "Nothing downloaded yet — open the Store to find archives"
                        : "Nothing downloaded yet. A few good places to start:");
        empty.setStyle("-fx-opacity: 0.6;");
        box.getChildren().add(empty);
        for (com.insula.catalog.StarterPicks.Resolved starter : starters) {
            box.getChildren().add(starterRow(starter));
        }
        Button store = new Button("Browse the Store →");
        store.setOnAction(e -> onOpenStore.run());
        box.getChildren().add(store);
        return box;
    }

    private Region starterRow(com.insula.catalog.StarterPicks.Resolved starter) {
        Label title = new Label(starter.group().title());
        title.setStyle("-fx-font-weight: bold;");
        Label blurb = new Label(
                starter.pick().blurb() + " · " + Formats.bytes(starter.entry().sizeBytes()));
        blurb.setStyle("-fx-opacity: 0.55; -fx-font-size: 0.85em;");
        VBox main = new VBox(2, title, blurb);
        HBox.setHgrow(main, Priority.ALWAYS);

        Button get = new Button("Download");
        get.setOnAction(e -> {
            get.setDisable(true);
            onDownloadStarter.accept(starter.entry());
        });

        HBox row = new HBox(12, monogram(starter.group().title()), main, get);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        return row;
    }

    private Region archiveRow(LibraryEntry entry) {
        StackPane icon = monogram(entry.title());

        Label title = new Label(entry.title());
        title.setStyle("-fx-font-weight: bold;");
        Label meta = new Label(Formats.bytes(entry.sizeBytes())
                + (entry.verified() ? " · verified ✓" : " · not verified")
                + " · " + entry.fileName());
        meta.setStyle("-fx-opacity: 0.55; -fx-font-size: 0.85em;");
        VBox main = new VBox(2, title, meta);
        HBox.setHgrow(main, Priority.ALWAYS);

        Button open = new Button("Open");
        open.setOnAction(e -> onOpenArchive.accept(entry.file()));

        HBox row = new HBox(12, icon, main);
        com.insula.catalog.ZimEntry replacement = updates.get(entry.fileName());
        if (replacement != null) {
            Button pill = new Button("Update to " + UpdateCheckDates.dateOf(replacement.fileName()));
            pill.setStyle("-fx-background-color: -color-accent-emphasis; -fx-text-fill: white;"
                    + " -fx-background-radius: 14;");
            pill.setOnAction(e -> {
                pill.setDisable(true);
                onUpdate.accept(replacement);
            });
            row.getChildren().add(pill);
            updatePillCount++;
        }
        row.getChildren().add(open);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-default;"
                + " -fx-border-radius: 8; -fx-background-radius: 8;");
        return row;
    }

    private void updateGauge() {
        DiskInfo info = diskInfo.get();
        double usedFraction = info.totalBytes() <= 0 ? 0 : (double) info.usedByArchives() / info.totalBytes();
        gaugeBar.setProgress(Math.min(1, usedFraction));
        gaugeLabel.setText(Formats.bytes(info.usedByArchives())
                + " in " + info.archiveCount() + (info.archiveCount() == 1 ? " archive" : " archives")
                + " · " + Formats.bytes(info.freeBytes()) + " free");
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.setStyle("-fx-font-size: 0.75em; -fx-opacity: 0.55;");
        return label;
    }

    private static StackPane monogram(String title) {
        String letter =
                title == null || title.isBlank() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        Rectangle bg = new Rectangle(34, 34);
        bg.setArcWidth(10);
        bg.setArcHeight(10);
        bg.setFill(Color.hsb(Math.floorMod(title == null ? 0 : title.hashCode(), 360), 0.35, 0.55));
        Text text = new Text(letter);
        text.setFont(Font.font(15));
        text.setFill(Color.WHITE);
        StackPane pane = new StackPane(bg, text);
        pane.setMinSize(34, 34);
        pane.setMaxSize(34, 34);
        return pane;
    }

    /** Label helper so the pill says which build it fetches, not just "Update". */
    private static final class UpdateCheckDates {
        static String dateOf(String fileName) {
            String date = com.insula.catalog.UpdateCheck.buildDateOf(fileName);
            return date.isEmpty() ? "latest" : date;
        }
    }

    // ------------------------------------------------------- package-visible test seams

    int arrivingRowsForTest() {
        return arrivingRows.getChildren().size();
    }

    int deviceRowsForTest() {
        return (int)
                deviceRows.getChildren().stream().filter(n -> n instanceof HBox).count();
    }

    String gaugeTextForTest() {
        return gaugeLabel.getText();
    }

    int updatePillsForTest() {
        return updatePillCount;
    }

    int attentionRowsForTest() {
        return attentionRows.getChildren().size();
    }

    int starterRowsForTest() {
        VBox box =
                deviceRows.getChildren().isEmpty() || !(deviceRows.getChildren().getFirst() instanceof VBox v)
                        ? null
                        : v;
        return box == null
                ? 0
                : (int) box.getChildren().stream()
                        .filter(n -> n instanceof HBox)
                        .count();
    }
}
