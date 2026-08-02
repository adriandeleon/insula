package com.insula.app;

/** Test access to the player's pure time formatting, which is package-private by design. */
public final class VideoPlayerPaneAccess {

    private VideoPlayerPaneAccess() {}

    public static String format(double seconds) {
        return VideoPlayerPane.format(seconds);
    }
}
