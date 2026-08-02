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
import com.insula.library.Shelf;

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
    private final javafx.scene.control.ComboBox<Shelf.GroupBy> groupBox = new javafx.scene.control.ComboBox<>();
    private final javafx.scene.control.ComboBox<Shelf.SortBy> sortBox = new javafx.scene.control.ComboBox<>();
    private final Label dragHint = new Label();
    private final HBox organizeBar = new HBox(10);
    private final HBox lanRow = new HBox(12);
    private final Label lanState = new Label();
    private final Button lanToggle = new Button();
    private final Button lanQr = new Button("Show QR");
    private LanActions lanActions = LanActions.NONE;
    private java.util.function.BiConsumer<Shelf.GroupBy, Shelf.SortBy> onArrangementChanged = (g, s2) -> {};
    private Consumer<List<LibraryEntry>> onReorder = list -> {};
    private Consumer<LibraryEntry> onTogglePin = entry -> {};
    private RowActions rowActions = RowActions.NONE;

    /** What a row's ⋯ menu can do; the pane owns the menu, the controller owns the doing. */
    interface RowActions {
        RowActions NONE = new RowActions() {};

        default void openInNewTab(LibraryEntry entry) {}

        default void moveToTheme(LibraryEntry entry) {}

        default void shareOnLan(LibraryEntry entry) {}

        default void checkForUpdate(LibraryEntry entry) {}

        default void verifyNow(LibraryEntry entry) {}

        default void reveal(LibraryEntry entry) {}

        default void delete(LibraryEntry entry) {}
    }

    void setRowActions(RowActions actions) {
        this.rowActions = actions == null ? RowActions.NONE : actions;
    }

    void setOrganizeHandlers(
            java.util.function.BiConsumer<Shelf.GroupBy, Shelf.SortBy> onArrangementChanged,
            Consumer<List<LibraryEntry>> onReorder,
            Consumer<LibraryEntry> onTogglePin) {
        this.onArrangementChanged = onArrangementChanged;
        this.onReorder = onReorder;
        this.onTogglePin = onTogglePin;
    }

    void setArrangement(Shelf.GroupBy groupBy, Shelf.SortBy sortBy) {
        groupBox.setValue(groupBy);
        sortBox.setValue(sortBy);
        rebuildDevice();
    }

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
        heading.getStyleClass().add("card-title");
        gauge.getChildren().addAll(heading, gaugeBar, gaugeLabel);
        gauge.setAlignment(Pos.CENTER_LEFT);
        gauge.setPadding(new Insets(12));
        gauge.getStyleClass().add("rowcard");
        gaugeLabel.getStyleClass().add("card-sub");

        buildOrganizeControls();
        arrivingSection.getChildren().addAll(sectionTitle("Arriving"), arrivingRows);
        attentionSection.getChildren().addAll(sectionTitle("Needs attention"), attentionRows);

        buildLanRow();
        content.setPadding(new Insets(14));
        content.getChildren().addAll(gauge, arrivingSection, attentionSection, organizeBar, deviceRows, lanRow);

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
        title.getStyleClass().add("card-title");
        Label meta = new Label("Failed checksum verification — repair re-downloads only the damaged parts");
        meta.getStyleClass().add("card-sub");
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
        row.getStyleClass().addAll("rowcard", "rowcard-attention");
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

    /**
     * The LAN row lives at the foot of the Library because sharing is a property of the shelf, not
     * a separate destination — the archives you would share are the ones listed directly above it.
     */
    private void buildLanRow() {
        Label heading = new Label("Share on local network");
        heading.getStyleClass().add("card-title");
        lanState.getStyleClass().add("card-sub");
        VBox main = new VBox(2, heading, lanState);
        HBox.setHgrow(main, Priority.ALWAYS);

        lanToggle.setOnAction(e -> lanActions.toggle());
        lanQr.setOnAction(e -> lanActions.showQr());
        Button choose = new Button("Choose archives…");
        choose.setOnAction(e -> lanActions.chooseArchives());

        lanRow.getChildren().addAll(main, choose, lanQr, lanToggle);
        lanRow.setAlignment(Pos.CENTER_LEFT);
        lanRow.getStyleClass().add("rowcard");
        setLanState(null, 0);
    }

    /** What the LAN row can ask for; the controller owns the server. */
    interface LanActions {
        LanActions NONE = new LanActions() {};

        default void toggle() {}

        default void showQr() {}

        default void chooseArchives() {}
    }

    void setLanActions(LanActions actions) {
        this.lanActions = actions == null ? LanActions.NONE : actions;
    }

    /** {@code url} null means not sharing; {@code selected} is 0 for "everything verified". */
    void setLanState(String url, int selected) {
        boolean sharing = url != null && !url.isBlank();
        lanState.setText(
                sharing
                        ? "Other devices on this network can read "
                                + (selected == 0 ? "your library" : selected + " archives") + " at " + url
                        : "Off — nothing is reachable from other devices"
                                + (selected == 0 ? "" : " · " + selected + " archives chosen"));
        lanToggle.setText(sharing ? "Stop sharing" : "Start sharing");
        lanQr.setDisable(!sharing);
    }

    private void buildOrganizeControls() {
        groupBox.getItems().setAll(Shelf.GroupBy.values());
        groupBox.setValue(Shelf.GroupBy.THEME);
        groupBox.setConverter(labels(Map.of(
                Shelf.GroupBy.THEME, "Theme",
                Shelf.GroupBy.LANGUAGE, "Language",
                Shelf.GroupBy.PUBLISHER, "Publisher",
                Shelf.GroupBy.NONE, "None — flat list")));
        sortBox.getItems().setAll(Shelf.SortBy.values());
        sortBox.setValue(Shelf.SortBy.CUSTOM);
        sortBox.setConverter(labels(Map.of(
                Shelf.SortBy.CUSTOM, "Custom — drag to order",
                Shelf.SortBy.RECENT, "Recently added",
                Shelf.SortBy.NAME, "Name",
                Shelf.SortBy.SIZE, "Size — largest first",
                Shelf.SortBy.BUILD_DATE, "Build date — newest first")));
        groupBox.valueProperty().addListener((obs, old, now) -> arrangementChanged());
        sortBox.valueProperty().addListener((obs, old, now) -> arrangementChanged());

        dragHint.getStyleClass().add("card-faint");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        organizeBar.setAlignment(Pos.CENTER_LEFT);
        organizeBar
                .getChildren()
                .addAll(
                        sectionTitle("On this device"),
                        new Label("Group"),
                        groupBox,
                        new Label("Sort"),
                        sortBox,
                        gap,
                        dragHint);
    }

    private <T> javafx.util.StringConverter<T> labels(Map<T, String> names) {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : names.getOrDefault(value, String.valueOf(value));
            }

            @Override
            public T fromString(String text) {
                return null;
            }
        };
    }

    private void arrangementChanged() {
        onArrangementChanged.accept(groupBox.getValue(), sortBox.getValue());
        rebuildDevice();
    }

    private boolean draggable() {
        return sortBox.getValue() == Shelf.SortBy.CUSTOM;
    }

    private void rebuildDevice() {
        deviceRows.getChildren().clear();
        updatePillCount = 0;
        List<LibraryEntry> entries = library.entries();
        organizeBar.setVisible(!entries.isEmpty());
        organizeBar.setManaged(!entries.isEmpty());
        // Handles appear only while Custom is chosen — dragging must not look available when the
        // arrangement it produces would be ignored.
        dragHint.setText(draggable() ? "★ pins to the top · drag ⠿ to reorder" : "★ pins to the top");

        if (entries.isEmpty()) {
            deviceRows.getChildren().add(emptyState());
            updateGauge();
            return;
        }
        for (Shelf.Group group : Shelf.arrange(entries, groupBox.getValue(), sortBox.getValue())) {
            Label heading = groupHeading(group.title() + "  (" + group.entries().size() + ")");
            deviceRows.getChildren().add(heading);
            for (LibraryEntry entry : group.entries()) {
                deviceRows.getChildren().add(archiveRow(entry));
            }
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
                        ? "Nothing downloaded yet — open the Catalog to find archives"
                        : "Nothing downloaded yet. A few good places to start:");
        empty.getStyleClass().add("card-sub");
        box.getChildren().add(empty);
        for (com.insula.catalog.StarterPicks.Resolved starter : starters) {
            box.getChildren().add(starterRow(starter));
        }
        Button store = new Button("Browse the Catalog →");
        store.setOnAction(e -> onOpenStore.run());
        box.getChildren().add(store);
        return box;
    }

    private Region starterRow(com.insula.catalog.StarterPicks.Resolved starter) {
        Label title = new Label(starter.group().title());
        title.getStyleClass().add("card-title");
        Label blurb = new Label(
                starter.pick().blurb() + " · " + Formats.bytes(starter.entry().sizeBytes()));
        blurb.getStyleClass().add("card-sub");
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
        row.getStyleClass().add("rowcard");
        return row;
    }

    private Region archiveRow(LibraryEntry entry) {
        StackPane icon = monogram(entry.title());

        Label title = new Label(entry.title());
        title.getStyleClass().add("card-title");
        // The kit's subline: variant · size · build · verified. The file name moves to the
        // tooltip — it is the least interesting fact on the row and the longest.
        StringBuilder facts = new StringBuilder();
        String variant = Shelf.variantOf(entry.fileName());
        if (!variant.isEmpty()) {
            facts.append(variant).append(" · ");
        }
        facts.append(Formats.bytes(entry.sizeBytes()));
        String build = Shelf.buildDateOf(entry);
        if (!build.isEmpty()) {
            facts.append(" · build ").append(build);
        }
        facts.append(entry.verified() ? " · verified ✓" : " · not verified");
        Label meta = new Label(facts.toString());
        meta.getStyleClass().add("card-sub");
        meta.setTooltip(new javafx.scene.control.Tooltip(entry.fileName()));
        VBox main = new VBox(2, title, meta);
        HBox.setHgrow(main, Priority.ALWAYS);

        // The star is the whole favourites model: one click lifts the archive above every group.
        Button star = new Button(entry.pinned() ? "★" : "☆");
        star.getStyleClass().add("star");
        star.setTooltip(new javafx.scene.control.Tooltip(entry.pinned() ? "Unpin" : "Pin to top"));
        star.setOnAction(e -> onTogglePin.accept(entry));

        Button open = new Button("Open");
        open.setOnAction(e -> onOpenArchive.accept(entry.file()));

        Label handle = new Label("⠿");
        handle.getStyleClass().add("card-faint");
        handle.setVisible(draggable());
        handle.setManaged(draggable());

        HBox row = new HBox(12, handle, icon, main, star);
        Label updatePill = updatePillFor(entry);
        if (updatePill != null) {
            row.getChildren().add(updatePill);
        }
        row.getChildren().addAll(open, rowMenu(entry));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("rowcard");
        if (draggable()) {
            installDrag(row, entry);
        }
        return row;
    }

    /** The row's ⋯ menu. Everything here acts on one archive, so it lives beside it. */
    private Region rowMenu(LibraryEntry entry) {
        javafx.scene.control.MenuButton menu = new javafx.scene.control.MenuButton("⋯");
        menu.getItems()
                .addAll(
                        item("Open in new tab", () -> rowActions.openInNewTab(entry)),
                        item(entry.pinned() ? "Unpin" : "Pin to top", () -> onTogglePin.accept(entry)),
                        item("Move to theme…", () -> rowActions.moveToTheme(entry)),
                        new javafx.scene.control.SeparatorMenuItem(),
                        item("Share on LAN", () -> rowActions.shareOnLan(entry)),
                        item("Check for update", () -> rowActions.checkForUpdate(entry)),
                        item("Verify integrity now", () -> rowActions.verifyNow(entry)),
                        new javafx.scene.control.SeparatorMenuItem(),
                        item("Reveal file", () -> rowActions.reveal(entry)),
                        item("Delete from disk…", () -> rowActions.delete(entry)));
        return menu;
    }

    private javafx.scene.control.MenuItem item(String label, Runnable action) {
        javafx.scene.control.MenuItem menuItem = new javafx.scene.control.MenuItem(label);
        menuItem.setOnAction(e -> action.run());
        return menuItem;
    }

    private Label updatePillFor(LibraryEntry entry) {
        com.insula.catalog.ZimEntry replacement = updates.get(entry.fileName());
        if (replacement == null) {
            return null;
        }
        updatePillCount++;
        Label pill =
                Pills.update(UpdateCheckDates.dateOf(replacement.fileName()), Formats.bytes(replacement.sizeBytes()));
        pill.setOnMouseClicked(e -> onUpdate.accept(replacement));
        pill.setTooltip(new javafx.scene.control.Tooltip("Download this build"));
        return pill;
    }

    /** Drag-to-reorder, offered only while Custom is the sort. */
    private void installDrag(HBox row, LibraryEntry entry) {
        row.setOnDragDetected(e -> {
            var board = row.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(entry.file().toString());
            board.setContent(content);
            e.consume();
        });
        row.setOnDragOver(e -> {
            if (e.getGestureSource() != row && e.getDragboard().hasString()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            e.consume();
        });
        row.setOnDragDropped(e -> {
            String dropped = e.getDragboard().getString();
            List<LibraryEntry> current = new java.util.ArrayList<>(
                    Shelf.arrange(library.entries(), groupBox.getValue(), Shelf.SortBy.CUSTOM).stream()
                            .flatMap(g -> g.entries().stream())
                            .toList());
            LibraryEntry moving = current.stream()
                    .filter(candidate -> candidate.file().toString().equals(dropped))
                    .findFirst()
                    .orElse(null);
            if (moving != null) {
                current.remove(moving);
                current.add(Math.min(current.indexOf(entry) + 1, current.size()), moving);
                onReorder.accept(Shelf.reorder(current));
            }
            e.setDropCompleted(moving != null);
            e.consume();
        });
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
        label.getStyleClass().add("hsec-title");
        return label;
    }

    /**
     * A group heading is <em>data</em> — a theme, a language, a publisher — so unlike the fixed
     * structural heads it is not upper-cased. "ENCYCLOPEDIAS &amp; REFERENCE" reads as shouting,
     * and a language code that is meaningfully lower-case ("en") should not be rewritten.
     */
    private static Label groupHeading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("hsec-title");
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

    /** The section headings currently rendered, in order, with their counts stripped. */
    List<String> groupTitlesForTest() {
        return deviceRows.getChildren().stream()
                .filter(n -> n instanceof Label label && label.getStyleClass().contains("hsec-title"))
                .map(n -> ((Label) n).getText().replaceAll("\\s+\\(\\d+\\)$", ""))
                .toList();
    }

    /** Archive titles in display order, so a sort can be asserted rather than inferred. */
    List<String> deviceTitlesForTest() {
        return deviceRows.getChildren().stream()
                .filter(n -> n instanceof HBox)
                .map(n -> ((HBox) n)
                        .getChildren().stream()
                                .filter(c -> c instanceof VBox)
                                .findFirst()
                                .map(c -> ((Label) ((VBox) c).getChildren().getFirst()).getText())
                                .orElse(""))
                .toList();
    }

    boolean dragHandlesShownForTest() {
        return deviceRows.getChildren().stream()
                .filter(n -> n instanceof HBox)
                .anyMatch(n -> ((HBox) n)
                        .getChildren().stream()
                                .anyMatch(
                                        c -> c instanceof Label label && "⠿".equals(label.getText()) && c.isManaged()));
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
