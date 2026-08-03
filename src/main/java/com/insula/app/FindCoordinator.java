package com.insula.app;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

import com.insula.reader.PageFind;

/**
 * Find on the current page: the bar, and the search behind it.
 *
 * <p>Second of the coordinators out of {@link ReaderController}. Self-contained in the way tabs
 * were — it owns a bar, a query and a tally, and nothing else in the window needs any of them.
 * What it cannot do itself it asks {@link Ops} for: running a script against the article, which is
 * the renderer's business.
 *
 * <p>The counting is the reason this is not a call to {@code window.find}. That moves the
 * browser's own selection, leaves nothing highlighted to page between, and reports no total — so
 * "3 of 17" would be impossible and a reader would have no way to tell whether anything lay
 * further down. See {@link PageFind}.
 */
final class FindCoordinator {

    /** What the coordinator needs from the window around it. */
    interface Ops {

        /** Brings the reader forward — there is no point searching a page that is not showing. */
        void showReader();

        /** Whether the reader is the surface on screen. */
        boolean readerShowing();

        /** Applies a stylesheet over whatever the archive shipped. */
        void injectCss(String css);

        /** Runs a script against the article, or returns null when the engine cannot. */
        Object runScript(String script);
    }

    private final TextField field = new TextField();
    private final Label count = new Label();
    private final HBox bar = new HBox(4);
    private final Ops ops;

    FindCoordinator(Ops ops) {
        this.ops = ops;
        build();
    }

    /** The bar, for the tab's navigation row. Hidden until asked for. */
    HBox bar() {
        return bar;
    }

    boolean showing() {
        return bar.isVisible();
    }

    /** The tally as displayed — "3 of 17", "no matches", or blank. */
    String tally() {
        return count.getText();
    }

    private void build() {
        field.setPromptText("Find on page");
        field.setPrefColumnCount(16);
        field.textProperty().addListener((obs, old, now) -> search(now, true));
        field.setOnAction(e -> step(true));
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
                step(false);
                e.consume();
            }
        });
        count.getStyleClass().add("card-faint");

        Button previous = button("‹", "Previous match (Shift+Enter)", () -> step(false));
        Button next = button("›", "Next match (Enter)", () -> step(true));
        Button close = button("✕", "Close (Esc)", this::close);

        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getChildren().addAll(field, count, previous, next, close);
        // Hidden until asked for: it is one more thing between the tabs and the article, and most
        // reading does not involve searching the page.
        bar.setVisible(false);
        bar.setManaged(false);
    }

    private static Button button(String glyph, String tip, Runnable action) {
        Button b = new Button(glyph);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        b.getStyleClass().add("tab-action");
        return b;
    }

    void open() {
        if (!ops.readerShowing()) {
            ops.showReader();
        }
        bar.setVisible(true);
        bar.setManaged(true);
        // Deferred as well as immediate: a node that has just become managed has not been laid out
        // yet, and focus asked for before that pass is dropped — the bar opens with the caret
        // still in the article and the first thing typed goes nowhere.
        field.requestFocus();
        javafx.application.Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        if (!field.getText().isBlank()) {
            search(field.getText(), true);
        }
    }

    /** Puts the bar away and clears the highlights — a page left marked up looks broken. */
    void close() {
        bar.setVisible(false);
        bar.setManaged(false);
        count.setText("");
        clearHighlights();
    }

    private void clearHighlights() {
        ops.runScript("if (window.__insulaFind) { window.__insulaFind.clear(); }");
    }

    /** Runs the search and reports the tally. {@code advance} jumps to the first hit. */
    private void search(String query, boolean advance) {
        if (query == null || query.isBlank()) {
            count.setText("");
            clearHighlights();
            return;
        }
        // The helper and its stylesheet are installed per search: a page load drops both, and
        // re-running them is cheaper than tracking which document is currently carrying them.
        ops.injectCss(PageFind.css());
        ops.runScript(PageFind.installScript());
        int hits = number(ops.runScript("window.__insulaFind.search(" + PageFind.quote(query) + ")"));
        if (hits == 0) {
            // Silence would read as "still searching"; the tally is the only feedback there is.
            count.setText("no matches");
            return;
        }
        if (advance) {
            step(true);
        } else {
            count.setText(hits + (hits == 1 ? " match" : " matches"));
        }
    }

    void step(boolean forward) {
        int index = number(ops.runScript(
                "window.__insulaFind ? window.__insulaFind." + (forward ? "next()" : "previous()") + " : 0"));
        int hits = number(ops.runScript("window.__insulaFind ? window.__insulaFind.count() : 0"));
        count.setText(hits == 0 ? "no matches" : index + " of " + hits);
    }

    /** A script result the engine could not produce is zero, not a crash. */
    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** Registers the commands this coordinator owns. */
    void registerCommands(com.insula.command.CommandRegistry commands) {
        commands.register("find.onPage", "Find on This Page", this::open);
        commands.register("find.next", "Find Next on This Page", () -> step(true));
        commands.register("find.previous", "Find Previous on This Page", () -> step(false));
    }
}
