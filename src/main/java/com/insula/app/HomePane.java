package com.insula.app;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import com.insula.download.DownloadManager;
import com.insula.reader.ArticleRef;
import com.insula.reader.ArticleStore;

/**
 * Home — the surface that answers "what do I read now?".
 *
 * <p>Deliberately not a management screen. The kit puts search, the last article, recents and
 * bookmarks here, and keeps everything with a control on it in the Library; the one exception is
 * a single-line strip when a download is arriving, which is a <em>teaser</em> with no pause or
 * cancel — it only links to the Library where the real row lives. That split is what stops Home
 * from silently becoming a second Library.
 */
final class HomePane {

    /** What Home needs to know about the article the reader left off in. */
    record Continue(ArticleRef article, double fraction, String when) {}

    private final ArticleStore bookmarks;
    private final ArticleStore history;
    private final DownloadManager downloads;
    private final Supplier<Continue> lastRead;
    private final Supplier<String> searchScope;
    private final Consumer<ArticleRef> onOpen;
    private final Runnable onSearch;
    private final Runnable onOpenLibrary;

    private final ScrollPane root;
    private final VBox content = new VBox(16);
    private List<com.insula.catalog.StarterPicks.Resolved> starters = List.of();
    private Consumer<com.insula.catalog.ZimEntry> onDownloadStarter = e -> {};
    private Runnable onOpenCatalog = () -> {};
    private Supplier<Boolean> emptyDevice = () -> false;

    /** Whether the device has no archives at all — the difference between "get some" and "read some". */
    void setEmptyDevice(Supplier<Boolean> emptyDevice) {
        this.emptyDevice = emptyDevice;
    }

    HomePane(
            ArticleStore bookmarks,
            ArticleStore history,
            DownloadManager downloads,
            Supplier<Continue> lastRead,
            Supplier<String> searchScope,
            Consumer<ArticleRef> onOpen,
            Runnable onSearch,
            Runnable onOpenLibrary) {
        this.bookmarks = bookmarks;
        this.history = history;
        this.downloads = downloads;
        this.lastRead = lastRead;
        this.searchScope = searchScope;
        this.onOpen = onOpen;
        this.onSearch = onSearch;
        this.onOpenLibrary = onOpenLibrary;

        content.setPadding(new Insets(34, 44, 40, 44));
        content.setMaxWidth(980);
        root = new ScrollPane(content);
        root.setFitToWidth(true);
    }

    Region node() {
        return root;
    }

    void activate() {
        rebuild();
    }

    private void rebuild() {
        content.getChildren().clear();
        content.getChildren().add(searchHero());

        Continue resume = lastRead.get();
        if (resume != null) {
            content.getChildren().addAll(sectionTitle("Continue reading"), continueCard(resume));
        }

        List<ArticleRef> recents = history.entries().stream()
                .filter(ref ->
                        resume == null || !ref.key().equals(resume.article().key()))
                .limit(4)
                .toList();
        if (!recents.isEmpty()) {
            content.getChildren().addAll(sectionTitle("Recent articles"), tiles(recents));
        }

        List<ArticleRef> saved = bookmarks.entries().stream().limit(4).toList();
        if (!saved.isEmpty()) {
            content.getChildren().addAll(sectionTitle("Bookmarks"), tiles(saved));
        }

        arrivingStrip().ifPresent(strip -> content.getChildren().add(strip));

        if (resume == null && recents.isEmpty() && saved.isEmpty()) {
            content.getChildren().add(firstRun());
        }
    }

    /**
     * First run is the same frame with starters where the history would be — not a separate
     * welcome screen. The distinction that matters is <em>why</em> there is nothing to show: an
     * empty device needs archives, whereas a stocked one just has not been read yet, and offering
     * downloads to someone who already has a library is nagging.
     */
    private Region firstRun() {
        VBox box = new VBox(10);
        box.getStyleClass().add("first-run");
        if (!emptyDevice.get()) {
            Label read = new Label("Nothing read yet — search above to find something.");
            read.getStyleClass().add("card-sub");
            box.getChildren().add(read);
            return box;
        }

        Label tagline = new Label("The library that works when nothing else does.");
        tagline.getStyleClass().add("card-title");
        Label sub = new Label(
                starters.isEmpty()
                        ? "Nothing downloaded yet — open the Catalog to find archives."
                        : "Nothing downloaded yet. A few good places to start:");
        sub.getStyleClass().add("card-sub");
        box.getChildren().addAll(tagline, sub);
        starters.forEach(starter -> box.getChildren().add(starterRow(starter)));

        Button catalog = new Button("Browse the Catalog →");
        catalog.setOnAction(e -> onOpenCatalog.run());
        box.getChildren().add(catalog);
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
        row.getStyleClass().add("rowcard");
        return row;
    }

    /**
     * The starter picks, and how to act on them. Resolved by the controller against the cached
     * catalog, so this never blocks or touches the network.
     */
    void setStarters(
            List<com.insula.catalog.StarterPicks.Resolved> starters,
            Consumer<com.insula.catalog.ZimEntry> onDownloadStarter,
            Runnable onOpenCatalog) {
        this.starters = List.copyOf(starters);
        this.onDownloadStarter = onDownloadStarter;
        this.onOpenCatalog = onOpenCatalog;
    }

    /** The hero: cross-archive search is the app's strongest feature, so it leads the surface. */
    private Region searchHero() {
        Label prompt = new Label(searchScope.get());
        prompt.getStyleClass().add("card-sub");
        Label hint = new Label("Ctrl+K");
        hint.getStyleClass().add("kbd");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox field = new HBox(10, new Label("🔍"), prompt, gap, hint);
        field.setAlignment(Pos.CENTER_LEFT);
        field.getStyleClass().add("rowcard");
        field.setOnMouseClicked(e -> onSearch.run());

        Label caption = new Label("works fully offline · results ranked exact → prefix → fuzzy");
        caption.getStyleClass().add("card-faint");
        VBox box = new VBox(6, field, caption);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** The saved scroll fraction becomes a visible "62% through" — the kit's one primary action. */
    private Region continueCard(Continue resume) {
        Label title = new Label(resume.article().title());
        title.getStyleClass().add("card-title");
        Label sub = new Label(resume.article().bookTitle()
                + (resume.when() == null || resume.when().isBlank() ? "" : " · " + resume.when()));
        sub.getStyleClass().add("card-sub");

        ProgressBar bar = new ProgressBar(resume.fraction());
        bar.setMaxWidth(Double.MAX_VALUE);
        Label percent = new Label(Math.round(resume.fraction() * 100) + "% through the article");
        percent.getStyleClass().add("card-faint");
        HBox progress = new HBox(10, bar, percent);
        HBox.setHgrow(bar, Priority.ALWAYS);
        progress.setAlignment(Pos.CENTER_LEFT);

        VBox main = new VBox(3, title, sub, progress);
        HBox.setHgrow(main, Priority.ALWAYS);

        Button resumeButton = new Button("Resume");
        resumeButton.setDefaultButton(true);
        resumeButton.setOnAction(e -> onOpen.accept(resume.article()));

        HBox card = new HBox(20, monogram(resume.article().title()), main, resumeButton);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("rowcard");
        return card;
    }

    private Region tiles(List<ArticleRef> refs) {
        TilePane grid = new TilePane(12, 12);
        grid.setPrefColumns(4);
        for (ArticleRef ref : refs) {
            Label title = new Label(ref.title());
            title.getStyleClass().add("card-title");
            title.setWrapText(false);
            Label source = new Label(ref.bookTitle());
            source.getStyleClass().add("card-faint");
            VBox tile = new VBox(3, title, source);
            tile.getStyleClass().add("rowcard");
            tile.setPrefWidth(215);
            tile.setOnMouseClicked(e -> onOpen.accept(ref));
            grid.getChildren().add(tile);
        }
        return grid;
    }

    /** A teaser, never a manager: no pause or cancel here, only a way to the Library. */
    private java.util.Optional<Region> arrivingStrip() {
        List<DownloadManager.Job> active = downloads.jobs().stream()
                .filter(job -> !job.snapshot().state().isTerminal())
                .toList();
        if (active.isEmpty()) {
            return java.util.Optional.empty();
        }
        DownloadManager.Job job = active.getFirst();
        Label title = new Label(
                job.entry().displayName() + (active.size() > 1 ? "  (+" + (active.size() - 1) + " more)" : ""));
        title.getStyleClass().add("card-title");
        Label facts = new Label(Formats.progressLine(job.snapshot(), job.transportName(), job.sourceNoun()));
        facts.getStyleClass().add("card-sub");
        VBox main = new VBox(2, title, facts);
        HBox.setHgrow(main, Priority.ALWAYS);

        Button toLibrary = new Button("Library →");
        toLibrary.setOnAction(e -> onOpenLibrary.run());

        HBox strip = new HBox(14, main, toLibrary);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().addAll("rowcard", "rowcard-arriving");
        return java.util.Optional.of(strip);
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text.toUpperCase(java.util.Locale.ROOT));
        label.getStyleClass().add("hsec-title");
        return label;
    }

    private static Region monogram(String title) {
        String letter =
                title == null || title.isBlank() ? "?" : title.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        Label label = new Label(letter);
        javafx.scene.layout.StackPane tile = new javafx.scene.layout.StackPane(label);
        tile.getStyleClass().add("tile");
        tile.setMinSize(56, 56);
        tile.setMaxSize(56, 56);
        return tile;
    }

    // ------------------------------------------------------- package-visible test seams

    int sectionCountForTest() {
        return (int) content.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("hsec-title"))
                .count();
    }

    boolean showsContinueCardForTest() {
        return content.getChildren().stream()
                .anyMatch(n -> n instanceof HBox box
                        && box.getStyleClass().contains("rowcard")
                        && box.getChildren().stream()
                                .anyMatch(c -> c instanceof Button b && "Resume".equals(b.getText())));
    }

    /** Only the first-run block's rows: the search hero is also an HBox.rowcard inside a VBox. */
    private java.util.stream.Stream<javafx.scene.Node> firstRunChildren() {
        return content.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("first-run"))
                .flatMap(n -> ((VBox) n).getChildren().stream());
    }

    int starterRowsForTest() {
        return (int) firstRunChildren()
                .filter(n -> n instanceof HBox && n.getStyleClass().contains("rowcard"))
                .count();
    }

    boolean showsTaglineForTest() {
        return firstRunChildren()
                .anyMatch(n -> n instanceof Label label
                        && "The library that works when nothing else does.".equals(label.getText()));
    }

    boolean showsArrivingStripForTest() {
        return content.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("rowcard-arriving"));
    }

    /** Path of the article the Continue card points at, for asserting it is the right one. */
    Path continueTargetForTest() {
        Continue resume = lastRead.get();
        return resume == null ? null : resume.article().archiveFile();
    }
}
