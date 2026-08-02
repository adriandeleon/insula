package com.insula.reader;

import java.util.function.Consumer;

/**
 * The object the placeholder's Play button calls through — the one JS→Java surface in the app.
 *
 * <p>It is deliberately a single method taking a single string, and the handler is injected, so
 * nothing reachable from page script can do more than ask the host to open one URL. The class
 * must be public and its package {@code opens} to {@code javafx.web}, because {@code
 * JSObject.setMember} dispatches reflectively.
 */
public final class MediaBridge {

    private final Consumer<String> onPlayExternal;

    public MediaBridge(Consumer<String> onPlayExternal) {
        this.onPlayExternal = onPlayExternal;
    }

    /** Called from page script. Named to read clearly in the JavaScript that calls it. */
    public void playExternal(String url) {
        if (url != null && !url.isBlank()) {
            onPlayExternal.accept(url);
        }
    }
}
