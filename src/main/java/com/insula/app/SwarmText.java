package com.insula.app;

import com.insula.download.ProgressSnapshot;
import com.insula.download.SwarmStats;

/**
 * Puts BitTorrent's numbers into words.
 *
 * <p>Pure and separate from the rows that show it, because the wording is the whole feature: a
 * swarm readout is only useful if it answers "is this working, and why not" without the reader
 * knowing what a leecher is. "0 peers" is the single most important thing this can say, and it has
 * to say it in the summary rather than bury it in a tooltip.
 */
final class SwarmText {

    private SwarmText() {}

    /** The one-line summary that sits on the row: peers first, because that is the health signal. */
    static String summary(SwarmStats swarm) {
        if (swarm == null) {
            return "";
        }
        if (swarm.peers() == 0) {
            return swarm.webSeeds() > 0
                    // Not a failure: web seeds are ordinary HTTP mirrors merged into the swarm,
                    // and a download with no peers at all still runs at full speed on them.
                    ? "no peers · " + count(swarm.webSeeds(), "mirror")
                    : "looking for peers";
        }
        String peers = count(swarm.peers(), "peer");
        return swarm.seeds() > 0 ? peers + " · " + swarm.seeds() + " with the whole file" : peers;
    }

    /** The fuller reading, for a tooltip. */
    static String detail(ProgressSnapshot snapshot) {
        SwarmStats swarm = snapshot == null ? null : snapshot.swarm();
        if (swarm == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(swarm.seeding() ? "Sharing this archive with others" : "Downloading over BitTorrent")
                .append('\n');
        sb.append("Connected peers: ").append(swarm.peers());
        if (swarm.peers() > 0) {
            sb.append("  (")
                    .append(swarm.seeds())
                    .append(" complete, ")
                    .append(swarm.leechers())
                    .append(" still downloading)");
        }
        sb.append('\n');
        if (swarm.webSeeds() > 0) {
            sb.append("HTTP mirrors in the swarm: ").append(swarm.webSeeds()).append('\n');
        }
        if (!swarm.seeding()) {
            sb.append("Down: ").append(Formats.rate(snapshot.bytesPerSecond())).append('\n');
        }
        sb.append("Up: ").append(Formats.rate(swarm.uploadRate())).append('\n');
        sb.append("Given back: ").append(Formats.bytes(swarm.uploaded()));
        if (snapshot.bytesCompleted() > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "  (%.2f×)", swarm.ratio(snapshot.bytesCompleted())));
        }
        return sb.toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
