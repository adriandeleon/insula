package com.insula.app;

import java.util.function.Function;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.insula.command.Command;
import com.insula.command.CommandRegistry;
import com.insula.command.PaletteFilter;

/**
 * In-scene command palette (Editora-style): a card over a dim, click-to-dismiss backdrop hosted
 * in the root {@link StackPane} — deliberately not a {@code Popup}, which is a separate native
 * window with unreliable focus behavior. Type to filter, ↑/↓ to move, Enter runs, Esc closes;
 * each row shows the command's keybinding. The command runs after the palette hides so focus is
 * already back where it belongs.
 */
final class CommandPalette {

    private final StackPane host;
    private final CommandRegistry registry;
    private final Function<String, String> shortcutFor;

    private final Region backdrop = new Region();
    private final VBox card = new VBox(8);
    private final TextField input = new TextField();
    private final ListView<Command> list = new ListView<>();
    private Node previousFocus;

    CommandPalette(StackPane host, CommandRegistry registry, Function<String, String> shortcutFor) {
        this.host = host;
        this.registry = registry;
        this.shortcutFor = shortcutFor;
        build();
    }

    private void build() {
        backdrop.getStyleClass().add("palette-backdrop");
        backdrop.setOnMouseClicked(e -> hide());

        input.setPromptText("Type a command…");
        input.textProperty().addListener((obs, old, text) -> refresh(text));
        input.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> {
                    hide();
                    e.consume();
                }
                case ENTER -> {
                    runSelected();
                    e.consume();
                }
                case DOWN -> {
                    move(1);
                    e.consume();
                }
                case UP -> {
                    move(-1);
                    e.consume();
                }
                default -> {
                    /* typing */
                }
            }
        });

        list.setFocusTraversable(false);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Command item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(item.title());
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                Label shortcut = new Label(shortcutFor.apply(item.id()));
                shortcut.getStyleClass().add("palette-shortcut");
                HBox row = new HBox(12, title, spacer, shortcut);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        list.setOnMouseClicked(e -> runSelected());
        list.setPrefHeight(320);

        card.getStyleClass().add("palette-card");
        card.setPadding(new Insets(10));
        card.setMaxWidth(560);
        card.setMaxHeight(400);
        card.getChildren().addAll(input, list);
        card.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);
        StackPane.setAlignment(card, Pos.TOP_CENTER);
        StackPane.setMargin(card, new Insets(90, 0, 0, 0));
    }

    boolean isShowing() {
        return host.getChildren().contains(card);
    }

    void toggle() {
        if (isShowing()) {
            hide();
        } else {
            show();
        }
    }

    void show() {
        if (isShowing()) {
            return;
        }
        previousFocus = host.getScene() == null ? null : host.getScene().getFocusOwner();
        host.getChildren().addAll(backdrop, card);
        input.clear();
        refresh("");
        input.requestFocus();
    }

    void hide() {
        host.getChildren().removeAll(backdrop, card);
        if (previousFocus != null) {
            previousFocus.requestFocus();
            previousFocus = null;
        }
    }

    private void refresh(String query) {
        list.getItems().setAll(PaletteFilter.filter(query, registry.all()));
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int next = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void runSelected() {
        Command selected = list.getSelectionModel().getSelectedItem();
        if (selected == null && !list.getItems().isEmpty()) {
            selected = list.getItems().get(0);
        }
        hide();
        if (selected != null) {
            registry.run(selected.id());
        }
    }
}
