package com.insula.net;

import java.util.function.Consumer;

/**
 * Keeps {@link NetworkState.Status} current and tells the UI when it changes.
 *
 * <p>Polls the local interface list on its own daemon thread — cheap, silent, and never a request
 * to anybody. Ten seconds is deliberate: this is an offline reader, and a wifi toggle showing up
 * within ten seconds is fast enough for something nothing depends on.
 *
 * <p>{@link #reportFailure()} is the other half. Live interfaces do not prove that kiwix.org can be
 * reached — a captive portal looks exactly like a working network — so anything that tries and
 * fails says so here, and the indicator follows what actually happened rather than what the
 * interface list implies.
 */
public final class NetworkMonitor {

    private static final long POLL_SECONDS = 10;

    private final Consumer<NetworkState.Status> onChange;
    private final java.util.concurrent.ScheduledExecutorService poller =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "network-monitor");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean reachable = true;
    private volatile boolean userChoseOffline;
    private volatile NetworkState.Status last;

    /** @param onChange called on the caller's marshalling thread with each new status */
    public NetworkMonitor(Consumer<NetworkState.Status> onChange) {
        this.onChange = onChange == null ? s -> {} : onChange;
    }

    public void start() {
        poller.scheduleWithFixedDelay(this::poll, 0, POLL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void stop() {
        poller.shutdownNow();
    }

    /** The user's switch. */
    public void setUserChoseOffline(boolean offline) {
        userChoseOffline = offline;
        publish();
    }

    public boolean userChoseOffline() {
        return userChoseOffline;
    }

    public NetworkState.Status status() {
        return NetworkState.of(reachable, userChoseOffline);
    }

    public boolean mayUseNetwork() {
        return NetworkState.mayUseNetwork(status());
    }

    /**
     * Something tried to use the network and could not.
     *
     * <p>Marks the app offline until the next poll rather than for good: a single failed request
     * can be one dead mirror, and latching would leave the catalog disabled long after the problem
     * had gone.
     */
    public void reportFailure() {
        reachable = false;
        publish();
    }

    /** Something used the network successfully — the fastest way back from a false negative. */
    public void reportSuccess() {
        if (!reachable) {
            reachable = true;
            publish();
        }
    }

    private void poll() {
        boolean now = Reachability.hasNetwork();
        if (now != reachable) {
            reachable = now;
        }
        publish();
    }

    private void publish() {
        NetworkState.Status now = status();
        if (now != last) {
            last = now;
            onChange.accept(now);
        }
    }
}
