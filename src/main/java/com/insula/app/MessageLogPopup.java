package com.insula.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Reads back the {@link MessageLog} — the status-bar messages from this session, newest first.
 *
 * <p>Shown in the shell's own {@link StackPane} rather than a {@code Popup}, for the reason the
 * command palette gives: a Popup is a separate native window, and on Windows it does not reliably
 * take keyboard focus, which leaves the keyboard dead in both windows at once.
 *
 * <p>Rows are multi-selectable and there is a Copy button, because the reason to open this is
 * usually to paste an error somewhere.
 */
final class MessageLogPopup {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final StackPane host;
    private final MessageLog log;
    private final Region backdrop = new Region();
    private final VBox card = new VBox(6);
    private final ListView<MessageLog.Entry> list = new ListView<>();
    private boolean showing;
    /** When it last hid, so the click that dismissed it does not immediately reopen it. */
    private long lastHiddenAt;

    MessageLogPopup(StackPane host, MessageLog log) {
        this.host = host;
        this.log = log;
        build();
    }

    private void build() {
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMouseClicked(e -> hide());

        Label header = new Label("Messages");
        header.getStyleClass().add("message-log-header");

        list.getStyleClass().add("message-log-list");
        list.setPrefSize(560, 300);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        Label empty = new Label("Nothing has been announced yet this session.");
        empty.getStyleClass().add("message-log-empty");
        list.setPlaceholder(empty);
        list.setCellFactory(v -> new ListCell<>() {
            private final Label time = new Label();
            private final Label text = new Label();
            private final HBox box = new HBox(10, time, text);

            {
                time.getStyleClass().add("message-log-time");
                time.setMinWidth(Region.USE_PREF_SIZE); // never clip the timestamp
                text.getStyleClass().add("message-log-text");
                text.setWrapText(true);
                box.setAlignment(Pos.TOP_LEFT); // the time stays at the top of a wrapped block
                HBox.setHgrow(text, Priority.ALWAYS);
                // wrapText needs a definite width to compute its height, so bind it to the
                // viewport less the time column; otherwise a long path scrolls sideways instead.
                text.maxWidthProperty().bind(list.widthProperty().subtract(110));
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(MessageLog.Entry item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                if (isEmpty || item == null) {
                    setGraphic(null);
                    return;
                }
                time.setText(
                        TIME.format(Instant.ofEpochMilli(item.epochMillis()).atZone(ZoneId.systemDefault())));
                text.setText(item.text());
                setGraphic(box);
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copySelection();
                e.consume();
            }
        });

        Button copy = new Button("Copy");
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            if (list.getSelectionModel().getSelectedItems().isEmpty()) {
                list.getSelectionModel().selectAll(); // nothing picked means "all of it"
            }
            copySelection();
        });
        Button clear = new Button("Clear");
        clear.setFocusTraversable(false);
        clear.setOnAction(e -> {
            log.clear();
            refresh();
        });
        Button close = new Button("Close");
        close.setFocusTraversable(false);
        close.setOnAction(e -> hide());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox footer = new HBox(6, copy, clear, gap, close);

        card.getStyleClass().add("message-log-card");
        card.getChildren().addAll(header, list, footer);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // Bottom left, over the status line it belongs to.
        StackPane.setAlignment(card, Pos.BOTTOM_LEFT);
    }

    private void copySelection() {
        String text = list.getSelectionModel().getSelectedItems().stream()
                .map(e -> TIME.format(Instant.ofEpochMilli(e.epochMillis()).atZone(ZoneId.systemDefault())) + "  "
                        + e.text())
                .collect(Collectors.joining(System.lineSeparator()));
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void refresh() {
        list.getItems().setAll(log.entries());
    }

    /** Click the status line again to dismiss — so the same target opens and closes it. */
    void toggle() {
        if (showing || System.currentTimeMillis() - lastHiddenAt < 200) {
            hide();
            return;
        }
        show();
    }

    void show() {
        if (showing) {
            return;
        }
        refresh();
        host.getChildren().addAll(backdrop, card);
        showing = true;
        list.requestFocus();
    }

    void hide() {
        if (!showing) {
            return;
        }
        host.getChildren().removeAll(backdrop, card);
        showing = false;
        lastHiddenAt = System.currentTimeMillis();
    }

    boolean isShowing() {
        return showing;
    }
}
