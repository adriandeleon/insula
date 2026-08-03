package com.insula.download;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * The decisions a torrent download makes, separated from the libtorrent calls that carry them out.
 *
 * <p>{@code TorrentTransport.Job} is the least testable class in the project — it forks a real
 * swarm — and it is also the one that has crashed the app. Everything it decides, as opposed to
 * everything it *does*, lives here instead: where the bytes are written, whether the transfer has
 * stalled, whether it finished, what may be deleted afterwards. Those are the judgements that
 * quarantined a four-percent file as corrupt and left a full-length husk in the library, and none
 * of them needed a peer to get wrong.
 *
 * <p>Pure. No libtorrent types appear in this file on purpose: a policy that needs a session to
 * exercise is a policy nobody exercises.
 */
public final class TorrentJobPolicy {

    /** How long a transfer may receive nothing before it is given up on and HTTP takes over. */
    public static final long STALL_TIMEOUT_NANOS = 45_000_000_000L;

    private TorrentJobPolicy() {}

    /**
     * Where libtorrent writes while the download runs — never the folder the file ends up in.
     *
     * <p>libtorrent preallocates at the full published length, so an interrupted torrent leaves a
     * full-length <em>sparse</em> file: real length, almost nothing on disk, every unwritten byte
     * reading as zero. Written straight into the archives folder, that husk sits exactly where the
     * library and the HTTP fallback both look, and a torrent abandoned at four percent is
     * indistinguishable from a finished download by anything that asks how long the file is.
     */
    public static Path workDir(Path destination, String entryName) {
        return destination.toAbsolutePath().getParent().resolve(".torrent-" + entryName);
    }

    /**
     * The file libtorrent actually produced.
     *
     * <p>The torrent's first file name is the usual answer, but a single-file torrent whose file
     * name differs from its display name writes under the latter — so the name is checked against
     * the disk rather than assumed.
     */
    public static Path producedFile(Path workDir, String firstFileName, String torrentName, Predicate<Path> exists) {
        Path first = workDir.resolve(firstFileName);
        return exists.test(first) ? first : workDir.resolve(torrentName);
    }

    /**
     * Whether the transfer is done.
     *
     * @param finished libtorrent's own verdict — every piece present and hash-checked
     * @param verified bytes that have passed their piece hash
     * @param total the torrent's total size
     */
    public static boolean complete(boolean finished, long verified, long total) {
        // total <= 0 would make any transfer instantly "complete", which is how a download of
        // nothing gets handed to verification and comes back corrupt.
        return finished || (total > 0 && verified >= total);
    }

    /**
     * Whether a transfer that is receiving nothing should be given up on.
     *
     * <p>Judged on bytes <em>received</em>, never on bytes verified: {@code totalDone} only
     * advances when a whole piece completes and passes its hash, so on a torrent with few large
     * pieces it reads zero for the entire transfer. Watching that made a perfectly healthy
     * single-piece download trip this timeout and report failure while data was actively arriving.
     *
     * <p>A paused transfer never stalls — it is not supposed to be receiving anything.
     */
    public static boolean stalled(long received, long lastReceived, long nanosSinceActivity, boolean paused) {
        return !paused && received <= lastReceived && nanosSinceActivity > STALL_TIMEOUT_NANOS;
    }

    /**
     * Whether the work dir may be deleted.
     *
     * <p>Only once the file has been delivered. A cancelled or failed multi-gigabyte torrent keeps
     * its pieces: libtorrent rechecks whatever is on disk when the torrent is added again, so
     * leaving them turns a second attempt into a resume. Throwing away six hundred megabytes
     * because somebody pressed Stop is not a cleanup.
     */
    public static boolean shouldCleanup(boolean succeeded) {
        return succeeded;
    }

    /**
     * Whether the libtorrent session should be stopped when the job ends.
     *
     * <p>Kept alive only when it is actually sharing something. A seeding session that never got
     * as far as registering a seed is just a leaked session, and one stopped while a seed still
     * points at it leaves the UI holding a handle into a session that no longer exists.
     */
    public static boolean shouldStopSession(boolean hasSeed, boolean cancelled) {
        return !hasSeed || cancelled;
    }
}
