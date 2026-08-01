package com.offlinewiki.download;

import java.nio.file.Path;

import com.offlinewiki.catalog.ZimEntry;

/**
 * A way of getting an archive's bytes onto disk. The seam exists so a BitTorrent transport can be
 * added later without touching the HTTP path — {@link TransportSelector} chooses between them and
 * falls back when one cannot get moving.
 */
public interface DownloadTransport {

    /** Stable id, e.g. {@code "http-multisource"} or {@code "torrent"}. */
    String id();

    /** Whether this transport can serve the entry (sidecar availability, network reachability). */
    boolean canHandle(ZimEntry entry);

    /** Begins the transfer immediately; progress arrives on the transport's own threads. */
    DownloadHandle start(ZimEntry entry, Path destination, ProgressListener listener);
}
