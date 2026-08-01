package com.insula.download;

/** Receives progress <b>off the FX thread</b>; implementations must not touch the scene graph. */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(ProgressSnapshot snapshot);

    ProgressListener NONE = snapshot -> {};
}
