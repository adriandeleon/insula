package com.insula.download;

/** Lifecycle of a single download, from queued through to a usable (or quarantined) file. */
public enum DownloadState {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    /** Bytes are on disk; the whole-file SHA-256 is being computed. */
    VERIFYING,
    /** Verified and ready to open. */
    COMPLETED,
    FAILED,
    CANCELLED,
    /** Downloaded but failed verification — kept on disk, never auto-opened. */
    QUARANTINED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == QUARANTINED;
    }
}
