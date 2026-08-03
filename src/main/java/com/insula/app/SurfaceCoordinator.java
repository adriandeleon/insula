package com.insula.app;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

import com.insula.command.CommandRegistry;

/**
 * Which of the four surfaces is on screen, and the switcher that says so.
 *
 * <p>Fourth of the coordinators out of {@link ReaderController}. The switching itself was four
 * near-identical methods — deactivate the others, set the centre, activate this one, record it,
 * resync the switcher — written out once each, which is four chances for one of them to forget a
 * step. There is one of them now, and each surface supplies only what is different about it.
 *
 * <p>It deliberately knows nothing about a Library or a Catalog. A surface is a node, something to
 * do when it appears and something to do when it goes away; keeping it to that is what stops this
 * turning into a second copy of the window.
 */
final class SurfaceCoordinator {

    enum Surface {
        HOME,
        LIBRARY,
        CATALOG,
        READER
    }

    /**
     * One surface.
     *
     * @param node what to put in the middle of the window
     * @param onShow run after it is placed — a pane that samples or refreshes starts here
     * @param onHide run when something else takes over, so nothing keeps working out of sight
     */
    record Pane(Supplier<Node> node, Runnable onShow, Runnable onHide) {

        static Pane of(Supplier<Node> node) {
            return new Pane(node, () -> {}, () -> {});
        }
    }

    /** What the coordinator needs from the window around it. */
    interface Ops {

        /** Puts a surface in the middle of the window. */
        void setCenter(Node node);

        /** Whether there is anything to read — the Reader is a surface only once there is. */
        boolean hasArchive();

        /** Run after every switch: the status bar and the omnibox both describe the surface. */
        void afterSwitch();
    }

    private final ToggleButton homeTab = new ToggleButton("Home");
    private final ToggleButton libraryTab = new ToggleButton("Library");
    private final ToggleButton catalogTab = new ToggleButton("Catalog");
    private final ToggleButton readerTab = new ToggleButton("Reader");
    private final HBox switcher = new HBox(homeTab, libraryTab, catalogTab, readerTab);

    private final Map<Surface, Pane> panes = new EnumMap<>(Surface.class);
    private final Ops ops;

    private Surface current = Surface.READER;

    SurfaceCoordinator(Ops ops) {
        this.ops = ops;
        ToggleGroup group = new ToggleGroup();
        for (ToggleButton tab : new ToggleButton[] {homeTab, libraryTab, catalogTab, readerTab}) {
            tab.setToggleGroup(group);
        }
        readerTab.setVisible(false);
        readerTab.setManaged(false);
        switcher.getStyleClass().add("surfaces");
        switcher.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    }

    /** The segmented switcher, for the toolbar. */
    HBox switcher() {
        return switcher;
    }

    void register(Surface surface, Pane pane) {
        panes.put(surface, pane);
    }

    /** Whether the Reader is offered at all — it is not, until something is open. */
    boolean readerAvailable() {
        return readerTab.isVisible();
    }

    Surface current() {
        return current;
    }

    boolean showing(Surface surface) {
        return current == surface;
    }

    /**
     * Brings a surface forward. A surface already showing is left alone rather than torn down and
     * rebuilt — activating a pane twice restarts whatever it samples.
     */
    void show(Surface surface) {
        if (current == surface) {
            return;
        }
        Pane pane = panes.get(surface);
        if (pane == null) {
            return;
        }
        panes.forEach((other, p) -> {
            if (other != surface) {
                p.onHide().run();
            }
        });
        ops.setCenter(pane.node().get());
        current = surface;
        pane.onShow().run();
        syncSwitcher();
    }

    /** The reader and the library, back and forth — the one pair worth a single key. */
    void toggleLibrary() {
        show(current == Surface.READER ? Surface.LIBRARY : Surface.READER);
    }

    /** Re-reads what the window can offer; call when an archive opens or closes. */
    void syncSwitcher() {
        homeTab.setSelected(current == Surface.HOME);
        libraryTab.setSelected(current == Surface.LIBRARY);
        catalogTab.setSelected(current == Surface.CATALOG);
        readerTab.setSelected(current == Surface.READER);
        // Reader is a surface only once there is something to read.
        boolean readable = ops.hasArchive();
        readerTab.setVisible(readable);
        readerTab.setManaged(readable);
        ops.afterSwitch();
    }

    void registerCommands(CommandRegistry commands) {
        homeTab.setOnAction(e -> commands.run("home.open"));
        libraryTab.setOnAction(e -> commands.run("library.open"));
        catalogTab.setOnAction(e -> commands.run("catalog.open"));
        readerTab.setOnAction(e -> commands.run("library.reader"));
    }
}
