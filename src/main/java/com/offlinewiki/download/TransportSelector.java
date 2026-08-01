package com.offlinewiki.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.offlinewiki.catalog.ZimEntry;

/**
 * Chooses a transport per entry.
 *
 * <p>HTTP is the guaranteed path and is always registered last as the fallback: a school or NGO
 * network with BitTorrent blocked must still be able to download. A large entry prefers an
 * optional torrent transport when one is registered and enabled, but the caller is expected to
 * fall back (see {@link #fallbackFor}) if it cannot get moving — leaving a user staring at 0 B/s
 * is the failure this ordering exists to prevent.
 */
public final class TransportSelector {

    /** Below this size the torrent overhead is not worth it. Configurable; brief suggests 5 GB. */
    public static final long DEFAULT_TORRENT_THRESHOLD = 5L * 1024 * 1024 * 1024;

    private final List<DownloadTransport> transports = new ArrayList<>();
    private final DownloadTransport fallback;
    private long torrentThreshold = DEFAULT_TORRENT_THRESHOLD;
    private boolean torrentEnabled = true;

    public TransportSelector(DownloadTransport fallback) {
        this.fallback = fallback;
    }

    /** Registers an optional transport (e.g. torrent) tried ahead of the HTTP fallback. */
    public void register(DownloadTransport transport) {
        transports.add(transport);
    }

    public void setTorrentEnabled(boolean torrentEnabled) {
        this.torrentEnabled = torrentEnabled;
    }

    public void setTorrentThreshold(long bytes) {
        this.torrentThreshold = bytes;
    }

    public DownloadTransport fallbackFor(ZimEntry entry) {
        return fallback;
    }

    /** The transport to try first. */
    public DownloadTransport select(ZimEntry entry) {
        if (torrentEnabled && entry.sizeBytes() >= torrentThreshold) {
            Optional<DownloadTransport> preferred =
                    transports.stream().filter(t -> t.canHandle(entry)).findFirst();
            if (preferred.isPresent()) {
                return preferred.get();
            }
        }
        return fallback;
    }

    /** Whether {@link #select} would pick something other than the guaranteed HTTP path. */
    public boolean prefersAlternate(ZimEntry entry) {
        return select(entry) != fallback;
    }
}
