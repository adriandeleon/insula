package com.insula.download;

/**
 * Whether an existing file at the destination may be treated as partly-downloaded bytes.
 *
 * <p>Pure, and its own class, because getting this wrong is how a download reports success on a
 * file it never fetched. Both transports <b>preallocate</b> — they create the file at its full
 * published length up front so out-of-order chunks can be written into it — which on any modern
 * filesystem produces a <em>sparse</em> file: full length, almost nothing on disk, and every
 * unwritten byte reads back as zero.
 *
 * <p>So "the file is already the right length" says nothing at all about whether its bytes are
 * real. The only thing that does is the record of which ranges were actually written: the HTTP
 * transport's {@code .part} bitmap, or libtorrent's own resume data. Judging by length instead
 * silently adopts an abandoned preallocated husk — a torrent that stopped at 4% left a full-length
 * file, HTTP then wrote nothing over it, and the whole-file SHA-256 failed on ten gigabytes of
 * holes, which the user was told was a "corrupt download" and invited to re-fetch.
 */
public final class PartialFile {

    private PartialFile() {}

    /**
     * Whether the bytes already on disk can be kept and resumed into.
     *
     * @param existingLength length of the file at the destination, or -1 when there is none
     * @param expectedLength the published length
     * @param haveResumeRecord whether a resume record for <em>this</em> file's written ranges exists
     */
    public static boolean resumable(long existingLength, long expectedLength, boolean haveResumeRecord) {
        return haveResumeRecord && existingLength > 0 && expectedLength > 0 && existingLength <= expectedLength;
    }

    /**
     * Whether the file must be emptied before writing. True whenever bytes are present that no
     * resume record vouches for — the safe direction, since re-fetching costs time but adopting
     * holes costs the user a file they are told is corrupt.
     */
    public static boolean mustRestart(long existingLength, long expectedLength, boolean haveResumeRecord) {
        return existingLength > 0 && !resumable(existingLength, expectedLength, haveResumeRecord);
    }
}
