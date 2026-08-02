package com.insula.app;

import java.util.Locale;

import com.insula.download.ProgressSnapshot;

/** Human-readable rendering of sizes, rates and progress. Pure, so it is unit-tested. */
public final class Formats {

    private Formats() {}

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    public static String bytes(long value) {
        if (value < 0) {
            return "—";
        }
        double scaled = value;
        int unit = 0;
        while (scaled >= 1024 && unit < UNITS.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return unit == 0
                ? value + " B"
                : String.format(Locale.ROOT, scaled >= 100 ? "%.0f %s" : "%.1f %s", scaled, UNITS[unit]);
    }

    public static String rate(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? "" : bytes(bytesPerSecond) + "/s";
    }

    public static String duration(long seconds) {
        if (seconds < 0) {
            return "";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        }
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /** The one-line status under a download row. */
    public static String progressLine(ProgressSnapshot s) {
        return progressLine(s, "", "sources");
    }

    /**
     * The progress line, naming the protocol that is actually moving the bytes. Which transport
     * won is user-visible on purpose: BitTorrent only applies above a size threshold and silently
     * falls back to HTTP, so without saying so the preference looks broken.
     *
     * @param transportName "HTTP"/"BitTorrent", or blank before a transport is chosen
     * @param sourceNoun what {@code connectedSources} counts — "mirrors" or "peers"
     */
    public static String progressLine(ProgressSnapshot s, String transportName, String sourceNoun) {
        String via = transportName == null || transportName.isBlank() ? "" : " · via " + transportName;
        String noun = sourceNoun == null || sourceNoun.isBlank() ? "sources" : sourceNoun;
        return switch (s.state()) {
            case QUEUED -> "Queued";
            case CONNECTING -> (s.detail().isBlank() ? "Connecting…" : s.detail()) + via;
            case DOWNLOADING -> {
                StringBuilder sb = new StringBuilder(bytes(s.bytesCompleted()));
                if (s.bytesTotal() > 0) {
                    sb.append(" of ").append(bytes(s.bytesTotal()));
                }
                if (s.bytesPerSecond() > 0) {
                    sb.append(" · ").append(rate(s.bytesPerSecond()));
                }
                long eta = s.etaSeconds();
                if (eta > 0) {
                    sb.append(" · ").append(duration(eta)).append(" left");
                }
                sb.append(via);
                if (s.connectedSources() > 0) {
                    sb.append(" · ").append(s.connectedSources()).append(" ").append(noun);
                }
                yield sb.toString();
            }
            case PAUSED -> "Paused at " + bytes(s.bytesCompleted());
            case VERIFYING -> "Verifying checksum… " + bytes(s.bytesCompleted());
            case COMPLETED -> s.detail().isBlank() ? "Ready" : s.detail();
            case FAILED -> "Failed" + (s.detail().isBlank() ? "" : ": " + s.detail());
            case CANCELLED -> "Cancelled";
            case QUARANTINED -> "Quarantined" + (s.detail().isBlank() ? "" : " — " + s.detail());
        };
    }
}
