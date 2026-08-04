package com.insula.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import com.insula.command.CommandRegistry;
import com.insula.fulltext.FullTextService;
import com.insula.search.LibrarySearch;
import com.insula.zim.ZimArchive;

/**
 * Full-text search, where the reader meets it.
 *
 * <p>Nothing indexes on its own. Indexing costs real time - seconds for a small archive, a quarter
 * of an hour for a Wikipedia - and an app that starts a job that long because somebody opened a
 * file has taken a decision it had no right to. So an archive is indexed when it is asked for, and
 * until then it says so rather than quietly returning fewer results than it should.
 */
final class FullTextCoordinator {

    /** What the coordinator needs from the window around it. */
    interface Ops {

        /** The archive being read, or null. */
        Path currentArchive();

        /** Announces something. Goes in the message log. */
        void setStatus(String message);

        /**
         * Shows progress without announcing it.
         *
         * <p>Separate from {@link #setStatus} on purpose: progress changes several times a second,
         * and putting it through the announcement path would bury every real message under a
         * thousand rows of a percentage counting up.
         */
        void setProgress(String message);

        /** How many results a search should return. */
        int searchLimit();
    }

    private final FullTextService service;
    private final Ops ops;

    /**
     * The job's own strip in the status bar.
     *
     * <p>Its own thing rather than words in the echo area, because indexing a Wikipedia is a
     * quarter of an hour: long enough that "how far along is it" and "make it stop" both have to
     * be answerable at a glance, and long enough that a message which the next announcement
     * overwrites is no use at all.
     */
    private final ProgressBar bar = new ProgressBar(0);

    private final Label caption = new Label();
    private final HBox strip = new HBox(8);

    FullTextCoordinator(Path configDir, Ops ops) {
        this.service = new FullTextService(configDir);
        this.ops = ops;
        buildStrip();
    }

    private void buildStrip() {
        bar.setPrefWidth(120);
        caption.getStyleClass().add("card-faint");
        Button stop = new Button("Stop");
        stop.setTooltip(new Tooltip("Stop indexing; what has been written is discarded"));
        stop.getStyleClass().add("tab-action");
        stop.setOnAction(e -> cancel());
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getChildren().addAll(caption, bar, stop);
        showStrip(false);
    }

    /** The status-bar strip. Hidden unless something is being indexed. */
    HBox strip() {
        return strip;
    }

    private void showStrip(boolean visible) {
        strip.setVisible(visible);
        strip.setManaged(visible);
    }

    void register(Path file, String title, ZimArchive archive) {
        service.register(file, title, archive);
    }

    void close() {
        service.close();
    }

    boolean isIndexing() {
        return service.isIndexing();
    }

    /** Whether this archive can be searched by its text. */
    boolean isIndexed(Path file) {
        return file != null && service.state(file) == FullTextService.State.READY;
    }

    /**
     * Adds text matches to a set of title matches.
     *
     * <p>Title matches come first and are never displaced. Somebody who typed the name of an
     * article means that article, and burying it under pages that merely mention it would make the
     * search worse for the common case in exchange for the rare one.
     */
    void appendTo(List<LibrarySearch.Result> titleHits, String query, Consumer<List<LibrarySearch.Result>> onMerged) {
        service.search(query, ops.searchLimit(), hits -> {
            List<LibrarySearch.Result> merged = merge(titleHits, hits);
            Platform.runLater(() -> onMerged.accept(merged));
        });
    }

    /** Title hits, then text hits that are not already among them. */
    private static List<LibrarySearch.Result> merge(
            List<LibrarySearch.Result> titleHits, List<FullTextService.Hit> textHits) {
        List<LibrarySearch.Result> merged = new ArrayList<>(titleHits);
        Set<String> seen = new HashSet<>();
        for (LibrarySearch.Result hit : titleHits) {
            seen.add(key(hit.archiveFile(), hit.fullPath()));
        }
        for (FullTextService.Hit hit : textHits) {
            if (seen.add(key(hit.archiveFile(), hit.path()))) {
                merged.add(new LibrarySearch.Result(hit.archiveTitle(), hit.archiveFile(), hit.title(), hit.path(), 0));
            }
        }
        return List.copyOf(merged);
    }

    private static String key(Path archive, String path) {
        return archive.toAbsolutePath() + " " + path;
    }

    /**
     * The nudge that makes the feature findable, or empty when there is nothing to say.
     *
     * <p>Shown after a search rather than on opening an archive: at that moment somebody has
     * demonstrably just looked for something, which is the only time an offer to search harder is
     * welcome rather than noise.
     */
    String offerForCurrentArchive() {
        Path current = ops.currentArchive();
        if (current == null || isIndexed(current) || service.isIndexing()) {
            return "";
        }
        return "Titles only - build a text index to search inside this archive";
    }

    /** Builds the index for the archive being read. */
    void buildForCurrent() {
        build(ops.currentArchive());
    }

    /** Builds the index for a named archive — the Library acts on rows, not on what is open. */
    void build(Path current) {
        if (current == null) {
            ops.setStatus("Open an archive first");
            return;
        }
        if (isIndexed(current)) {
            ops.setStatus("This archive is already searchable by its text");
            return;
        }
        if (service.isIndexing()) {
            ops.setStatus("Already building an index - one at a time");
            return;
        }
        ops.setStatus("Building a text index. Searching still works meanwhile.");
        showStrip(true);
        service.buildIndex(
                current,
                progress -> Platform.runLater(() -> paint(progress)),
                ok -> Platform.runLater(() -> {
                    showStrip(false);
                    ops.setStatus(
                            ok
                                    ? "Text index ready - searches now look inside this archive"
                                    : "Indexing stopped; nothing was left half-built");
                }));
    }

    private void paint(FullTextService.Progress progress) {
        double fraction = progress.fraction();
        // An unknown total shows as indeterminate rather than as zero: a bar sitting at the far
        // left says "stuck", which is the opposite of what is happening.
        bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
        caption.setText(describe(progress));
    }

    /** "Indexing MDWiki - 41%". The article count lives in the tooltip; the strip is narrow. */
    static String describe(FullTextService.Progress progress) {
        double fraction = progress.fraction();
        String percent = fraction < 0 ? "" : " - " + Math.round(fraction * 100) + "%";
        return "Indexing " + progress.archiveTitle() + percent;
    }

    void cancel() {
        if (!service.isIndexing()) {
            ops.setStatus("Nothing is being indexed");
            return;
        }
        service.cancelIndexing();
        ops.setStatus("Stopping - the partial index is discarded rather than left to answer badly");
    }

    void deleteForCurrent() {
        delete(ops.currentArchive());
    }

    void delete(Path current) {
        if (current == null || !isIndexed(current)) {
            ops.setStatus("This archive has no text index");
            return;
        }
        long freed = service.indexBytes(current);
        if (service.deleteIndex(current)) {
            ops.setStatus("Deleted the text index - " + Formats.bytes(freed) + " freed");
        } else {
            ops.setStatus("Could not delete the text index");
        }
    }

    /** What every index costs, for Settings. */
    long totalIndexBytes() {
        return service.totalIndexBytes();
    }

    void registerCommands(CommandRegistry commands) {
        commands.register("search.buildIndex", "Build a Text Index for This Archive", this::buildForCurrent);
        commands.register("search.cancelIndex", "Stop Building the Text Index", this::cancel);
        commands.register("search.deleteIndex", "Delete This Archive's Text Index", this::deleteForCurrent);
    }
}
