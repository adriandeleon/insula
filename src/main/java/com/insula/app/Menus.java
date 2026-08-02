package com.insula.app;

import java.util.List;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import com.insula.command.Command;
import com.insula.command.CommandRegistry;
import com.insula.command.Keybindings;

/**
 * The menubar, built entirely out of registered commands.
 *
 * <p>Every item is a command id rather than a handler, which is what makes it impossible for the
 * menubar and the command palette to disagree: an item can only exist if the command does, its
 * label is the command's own title, and its accelerator is read from the same
 * {@link Keybindings} the scene installs. {@link #missingCommands} makes that a testable
 * property instead of a convention.
 */
final class Menus {

    /** A menu item: a command id, or {@link #SEPARATOR}. */
    private static final String SEPARATOR = "-";

    /** The kit's six menus, in order. */
    private static final List<MenuSpec> MENUS = List.of(
            new MenuSpec(
                    "File",
                    List.of(
                            "tab.new",
                            "file.open",
                            SEPARATOR,
                            "tab.close",
                            "tab.reopen",
                            SEPARATOR,
                            "file.closeArchive",
                            SEPARATOR,
                            "app.quit")),
            new MenuSpec(
                    "View",
                    List.of(
                            "home.open",
                            "library.open",
                            "catalog.open",
                            SEPARATOR,
                            "readerview.toggle",
                            SEPARATOR,
                            "view.zoomIn",
                            "view.zoomOut",
                            "view.zoomReset",
                            SEPARATOR,
                            "view.toggleTheme",
                            "view.commandPalette")),
            new MenuSpec("Bookmarks", List.of("bookmark.toggle", "bookmark.show", "history.show")),
            new MenuSpec(
                    "Library",
                    List.of(
                            "library.checkUpdates",
                            "library.repairAll",
                            SEPARATOR,
                            "lan.share",
                            "lan.qr",
                            SEPARATOR,
                            "library.reveal")),
            new MenuSpec("Catalog", List.of("catalog.refresh", "download.pauseAll", "download.resumeAll")),
            new MenuSpec("Help", List.of("help.shortcuts", "help.about")));

    private record MenuSpec(String title, List<String> items) {}

    private Menus() {}

    static MenuBar build(CommandRegistry registry, Keybindings keys) {
        MenuBar bar = new MenuBar();
        for (MenuSpec spec : MENUS) {
            Menu menu = new Menu(spec.title());
            for (String id : spec.items()) {
                if (SEPARATOR.equals(id)) {
                    menu.getItems().add(new SeparatorMenuItem());
                    continue;
                }
                registry.all().stream()
                        .filter(c -> c.id().equals(id))
                        .findFirst()
                        .ifPresent(command -> menu.getItems().add(item(command, registry, keys)));
            }
            bar.getMenus().add(menu);
        }
        return bar;
    }

    private static MenuItem item(Command command, CommandRegistry registry, Keybindings keys) {
        MenuItem item = new MenuItem(command.title());
        item.setOnAction(e -> registry.run(command.id()));
        var combination = keys.combinationFor(command.id());
        if (combination != null) {
            item.setAccelerator(combination);
        }
        return item;
    }

    /**
     * Ids the menubar names that the registry does not have. A menu item that silently vanishes is
     * worse than one that fails loudly, so this is asserted rather than trusted.
     */
    static List<String> missingCommands(CommandRegistry registry) {
        return MENUS.stream()
                .flatMap(m -> m.items().stream())
                .filter(id -> !SEPARATOR.equals(id))
                .distinct()
                .filter(id -> registry.all().stream().noneMatch(c -> c.id().equals(id)))
                .toList();
    }
}
