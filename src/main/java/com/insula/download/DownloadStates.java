package com.insula.download;

/**
 * Which download states are worth keeping a job around for.
 *
 * <p>Pure, and its own class, because getting this wrong strands an archive: the manager keys live
 * jobs by entry id and never removed them, so a cancelled download left a dead job in the map
 * forever. {@code enqueue} then handed that corpse straight back instead of starting anything, and
 * the catalog card kept rendering its final state — so cancelling a download was permanent, and
 * the button to start it again never came back. The partial bytes were on disk the whole time,
 * ready to resume.
 */
public final class DownloadStates {

    private DownloadStates() {}

    /**
     * Whether a job in this state should be thrown away and started afresh when asked again.
     *
     * <p>Cancelled and failed only. A job still working is returned as it is — asking twice for a
     * download already running must not start a second one — and a completed or quarantined one
     * has produced a file, which is the library's business rather than something to silently
     * re-fetch.
     */
    public static boolean restartable(DownloadState state) {
        return state == DownloadState.CANCELLED || state == DownloadState.FAILED;
    }

    /**
     * Whether a job in this state still deserves a row.
     *
     * <p>Deliberately <em>not</em> the inverse of {@link #restartable}: both a cancel and a failure
     * stop a transfer and both can be started again, but only one of them was asked for. A cancel
     * is the user saying they are done, so its row goes; a failure is something that happened to
     * them, and a row that vanishes takes the reason with it and leaves them wondering whether the
     * download is still running. The failed row carries its own Retry.
     */
    public static boolean worthShowing(DownloadState state) {
        return state != DownloadState.CANCELLED;
    }
}
