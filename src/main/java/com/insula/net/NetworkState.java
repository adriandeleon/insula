package com.insula.net;

/**
 * Whether the app should be reaching the network, and what to tell the reader.
 *
 * <p>Two independent facts, deliberately kept apart: whether a network is <em>reachable</em>, which
 * the app observes, and whether the user has said to <em>stay off it</em>, which only they decide.
 * Collapsing them into one boolean loses the difference between "there is no connection" and "you
 * asked me not to use one", and those need different words and different fixes.
 *
 * <p>Pure, so the wording and the precedence can be tested without a network.
 */
public final class NetworkState {

    /** What the indicator says. */
    public enum Status {
        /** A network is reachable and the app may use it. */
        ONLINE,
        /** The user has switched the app off the network. */
        OFFLINE_BY_CHOICE,
        /** No network is reachable. */
        OFFLINE
    }

    private NetworkState() {}

    /** The user's choice wins: asking to stay offline is not overridden by a working connection. */
    public static Status of(boolean networkReachable, boolean userChoseOffline) {
        if (userChoseOffline) {
            return Status.OFFLINE_BY_CHOICE;
        }
        return networkReachable ? Status.ONLINE : Status.OFFLINE;
    }

    /** Whether the app may make a request. */
    public static boolean mayUseNetwork(Status status) {
        return status == Status.ONLINE;
    }

    /** The status-bar label. */
    public static String label(Status status) {
        return switch (status) {
            case ONLINE -> "Online";
            case OFFLINE_BY_CHOICE -> "Offline";
            case OFFLINE -> "No connection";
        };
    }

    /** The tooltip, which is where the difference between the two offlines is explained. */
    public static String tooltip(Status status) {
        return switch (status) {
            case ONLINE -> "Connected. Click to work offline.";
            case OFFLINE_BY_CHOICE -> "You have set Insula to stay off the network. Click to go back online.";
            case OFFLINE -> "No network was found. Everything already downloaded still works.";
        };
    }

    /** Why an action was refused, in words that say which of the two offlines it was. */
    public static String refusal(Status status, String action) {
        return switch (status) {
            case OFFLINE_BY_CHOICE -> "Insula is set to work offline — " + action + " needs the network";
            case OFFLINE -> "No connection — " + action + " needs the network";
            case ONLINE -> "";
        };
    }
}
