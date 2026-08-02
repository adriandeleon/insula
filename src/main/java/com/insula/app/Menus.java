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

    /** A menu item: a command id, {@link #SEPARATOR}, or one of the dynamic-submenu markers. */
    private static final String SEPARATOR = "-";

    /** Placeholders the builder replaces with a live submenu. */
    private static final String RECENT_ARCHIVES = "@recent";

    private static final String RECENT_BOOKMARKS = "@bookmarks";

    /** One row of a dynamic submenu: what it says, and what it does. */
    record Entry(String label, Runnable action) {}

    /**
     * The lists that change while the app runs.
     *
     * <p>Both are read when the menu is <em>opened</em> rather than pushed on every change. A menu
     * that is not on screen cannot be looked at, so there is nothing to keep in sync — and the
     * push version has to be re-run from every site that opens an archive or adds a bookmark,
     * which is exactly the kind of wiring that rots into a stale menu.
     */
    interface Dynamic {
        Dynamic NONE = new Dynamic() {};

        default List<Entry> recentArchives() {
            return List.of();
        }

        default List<Entry> bookmarks() {
            return List.of();
        }
    }

    /** The kit's six menus, in order. */
    private static final List<MenuSpec> MENUS = List.of(
            new MenuSpec(
                    "File",
                    List.of(
                            "tab.new",
                            "file.open",
                            RECENT_ARCHIVES,
                            "library.import",
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
            new MenuSpec("Bookmarks", List.of("bookmark.toggle", "bookmark.show", "history.show", RECENT_BOOKMARKS)),
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
            new MenuSpec(
                    "Catalog",
                    List.of(
                            "catalog.refresh",
                            "catalog.starterPicks",
                            SEPARATOR,
                            "download.pauseAll",
                            "download.resumeAll")),
            new MenuSpec("Help", List.of("help.shortcuts", "help.zimFormat", "help.about")));

    private record MenuSpec(String title, List<String> items) {}

    private Menus() {}

    static MenuBar build(CommandRegistry registry, Keybindings keys) {
        return build(registry, keys, Dynamic.NONE);
    }

    static MenuBar build(CommandRegistry registry, Keybindings keys, Dynamic dynamic) {
        MenuBar bar = new MenuBar();
        for (MenuSpec spec : MENUS) {
            Menu menu = new Menu(spec.title());
            for (String id : spec.items()) {
                if (SEPARATOR.equals(id)) {
                    menu.getItems().add(new SeparatorMenuItem());
                    continue;
                }
                if (RECENT_ARCHIVES.equals(id)) {
                    menu.getItems().add(submenu("Open recent", dynamic::recentArchives, "Nothing opened yet"));
                    continue;
                }
                if (RECENT_BOOKMARKS.equals(id)) {
                    menu.getItems().add(new SeparatorMenuItem());
                    menu.getItems().add(submenu("Recent bookmarks", dynamic::bookmarks, "No bookmarks yet"));
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

    /**
     * A submenu filled when it opens. The disabled placeholder matters: an empty submenu renders
     * as a title that expands into nothing, which reads as broken rather than as "you have none".
     */
    private static Menu submenu(String title, java.util.function.Supplier<List<Entry>> entries, String whenEmpty) {
        Menu menu = new Menu(title);
        // A menu with no children never fires onShowing, so it needs one to begin with.
        menu.getItems().add(new MenuItem(whenEmpty));
        menu.setOnShowing(e -> {
            List<Entry> rows = entries.get();
            menu.getItems().clear();
            if (rows.isEmpty()) {
                MenuItem empty = new MenuItem(whenEmpty);
                empty.setDisable(true);
                menu.getItems().add(empty);
                return;
            }
            for (Entry entry : rows) {
                MenuItem item = new MenuItem(entry.label());
                item.setOnAction(a -> entry.action().run());
                menu.getItems().add(item);
            }
        });
        return menu;
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
                .filter(id -> !SEPARATOR.equals(id) && !id.startsWith("@"))
                .distinct()
                .filter(id -> registry.all().stream().noneMatch(c -> c.id().equals(id)))
                .toList();
    }
}
