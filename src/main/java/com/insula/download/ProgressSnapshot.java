package com.insula.download;

/**
 * An immutable point-in-time view of a download. Transports produce these on their own threads;
 * the UI coalesces them and repaints on a fixed schedule (never one FX hop per event).
 *
 * @param connectedSources active mirror connections, or peers for a torrent transport
 * @param swarm BitTorrent detail, or null when this is not a torrent — null rather than a row of
 *     zeroes, which would read like a stalled swarm instead of "no swarm here"
 */
public record ProgressSnapshot(
        DownloadState state,
        long bytesCompleted,
        long bytesTotal,
        long bytesPerSecond,
        int connectedSources,
        String detail,
        SwarmStats swarm) {

    /** The common case: everything a transport reports without a swarm behind it. */
    public ProgressSnapshot(
            DownloadState state,
            long bytesCompleted,
            long bytesTotal,
            long bytesPerSecond,
            int connectedSources,
            String detail) {
        this(state, bytesCompleted, bytesTotal, bytesPerSecond, connectedSources, detail, null);
    }

    public static ProgressSnapshot of(DownloadState state, long completed, long total) {
        return new ProgressSnapshot(state, completed, total, 0, 0, "");
    }

    /** The same snapshot with swarm detail attached. */
    public ProgressSnapshot withSwarm(SwarmStats stats) {
        return new ProgressSnapshot(state, bytesCompleted, bytesTotal, bytesPerSecond, connectedSources, detail, stats);
    }

    /** Whether this transfer is giving back rather than taking. */
    public boolean isSeeding() {
        return swarm != null && swarm.seeding();
    }

    /** 0.0–1.0, or -1 when the total size is unknown (an indeterminate bar). */
    public double fraction() {
        if (bytesTotal <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) bytesCompleted / bytesTotal);
    }

    /** Seconds remaining at the current rate, or -1 when not estimable. */
    public long etaSeconds() {
        if (bytesPerSecond <= 0 || bytesTotal <= 0 || bytesCompleted >= bytesTotal) {
            return -1;
        }
        return (bytesTotal - bytesCompleted) / bytesPerSecond;
    }
}
