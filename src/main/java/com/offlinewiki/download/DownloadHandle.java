package com.offlinewiki.download;

import java.util.concurrent.CompletableFuture;

/** Control surface for an in-flight download. */
public interface DownloadHandle {

    void pause();

    void resume();

    /** Stops the transfer; partial bytes and resume state are left on disk. */
    void cancel();

    /** Completes when the transfer finishes, is cancelled, or fails. Never completes exceptionally. */
    CompletableFuture<DownloadResult> completion();

    /** The most recent progress, for a UI that polls on a timer rather than reacting per event. */
    ProgressSnapshot snapshot();
}
