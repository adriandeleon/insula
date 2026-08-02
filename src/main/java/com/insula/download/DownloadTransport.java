package com.insula.download;

import java.nio.file.Path;

import com.insula.catalog.ZimEntry;

/**
 * A way of getting an archive's bytes onto disk. The seam exists so a BitTorrent transport can be
 * added later without touching the HTTP path — {@link TransportSelector} chooses between them and
 * falls back when one cannot get moving.
 */
public interface DownloadTransport {

    /** Stable id, e.g. {@code "http-multisource"} or {@code "torrent"}. */
    String id();

    /**
     * The protocol as a user would name it ("HTTP", "BitTorrent"). Shown on the download row —
     * which transport actually moved the bytes is not an implementation detail to a user deciding
     * whether to leave BitTorrent on.
     */
    default String displayName() {
        return id();
    }

    /** What this transport's {@code connectedSources} are called: "mirrors" for HTTP, "peers". */
    default String sourceNoun() {
        return "sources";
    }

    /** Whether this transport can serve the entry (sidecar availability, network reachability). */
    boolean canHandle(ZimEntry entry);

    /** Begins the transfer immediately; progress arrives on the transport's own threads. */
    DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener);
}
