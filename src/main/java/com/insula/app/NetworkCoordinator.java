package com.insula.app;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import com.insula.command.CommandRegistry;
import com.insula.config.Settings;
import com.insula.net.NetworkMonitor;
import com.insula.net.NetworkState;

/**
 * Whether Insula is on the network, what it says about it, and what it refuses when it is not.
 *
 * <p>Third of the coordinators out of {@link ReaderController}. It owns the monitor, the status
 * segment that reports it, and the switch that turns it off — and, more usefully, the one place
 * anything asks permission to make a request.
 *
 * <p>Two kinds of offline are kept apart all the way to the wording, because they are different
 * situations with different fixes: no connection is something to go and solve, while working
 * offline is a choice already made. Telling someone "No connection" when they asked for this sends
 * them looking for a network problem they do not have. See {@link NetworkState}.
 */
final class NetworkCoordinator {

    private final Label segment = new Label();
    private final NetworkMonitor monitor;
    private final Settings settings;
    private final java.util.function.Consumer<String> onStatus;

    NetworkCoordinator(Settings settings, java.util.function.Consumer<String> onStatus) {
        this.settings = settings;
        this.onStatus = onStatus;
        this.monitor = new NetworkMonitor(status -> Platform.runLater(() -> paint(status)));
        // Insula works without a connection by design, so which of the two it is right now is
        // worth a permanent segment rather than something that appears only when a request fails.
        segment.getStyleClass().add("status-network");
    }

    /** The status-bar segment. */
    Label segment() {
        return segment;
    }

    /** Starts watching, from the choice the last session left. */
    void start() {
        monitor.setUserChoseOffline(settings.isWorkOffline());
        paint(monitor.status());
        monitor.start();
    }

    void stop() {
        monitor.stop();
    }

    /** The label as displayed — "Online", "Offline", "No connection". */
    String label() {
        return segment.getText();
    }

    /**
     * Whether an action needing the network may go ahead, saying why when it may not.
     *
     * @param action what was being attempted, phrased to follow "…needs the network"
     */
    boolean require(String action) {
        if (monitor.mayUseNetwork()) {
            return true;
        }
        onStatus.accept(NetworkState.refusal(monitor.status(), action));
        return false;
    }

    /** Puts the app on or off the network, and says which it now is. */
    void toggleOffline() {
        boolean offline = !settings.isWorkOffline();
        settings.setWorkOffline(offline);
        settings.save();
        monitor.setUserChoseOffline(offline);
        onStatus.accept(
                offline
                        ? "Working offline — downloads and the catalog are paused; everything downloaded still opens"
                        : "Back online");
    }

    private void paint(NetworkState.Status status) {
        segment.setText(NetworkState.label(status));
        segment.setTooltip(new Tooltip(NetworkState.tooltip(status)));
        segment.getStyleClass().removeAll("status-network-off", "status-network-on");
        segment.getStyleClass().add(status == NetworkState.Status.ONLINE ? "status-network-on" : "status-network-off");
    }

    void registerCommands(CommandRegistry commands) {
        commands.register("network.toggleOffline", "Toggle: Work Offline", this::toggleOffline);
        segment.setOnMouseClicked(e -> commands.run("network.toggleOffline"));
    }
}
