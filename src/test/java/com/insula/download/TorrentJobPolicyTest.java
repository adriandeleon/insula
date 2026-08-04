package com.insula.download;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every judgement a torrent download makes, without a peer in sight. */
class TorrentJobPolicyTest {

    // Absolutised, because workDir absolutises the destination before taking its parent. A
    // "/home/x" literal is already absolute on Linux and macOS but only drive-*relative* on
    // Windows, so there workDir answered D:\home\x\... against an expectation of \home\x\...
    private static final Path ARCHIVES = Path.of("/home/x/.insula/archives").toAbsolutePath();
    private static final Path DEST = ARCHIVES.resolve("mdwiki_en_all_2025-11.zim");

    @Test
    void theBytesAreNeverWrittenIntoTheArchivesFolder() {
        // A cancelled torrent leaves a full-length sparse file behind. In the archives folder that
        // husk is indistinguishable from a finished download to anything that checks the length.
        Path work = TorrentJobPolicy.workDir(DEST, "mdwiki_en_all");
        assertEquals(ARCHIVES.resolve(".torrent-mdwiki_en_all"), work);
        assertFalse(work.equals(ARCHIVES), "the work dir is not the library");
        assertFalse(work.equals(DEST.getParent()));
    }

    @Test
    void theProducedFileIsCheckedAgainstTheDiskRatherThanAssumed() {
        Path work = Path.of("/tmp/w");
        Path byFileName = work.resolve("real.zim");
        assertEquals(byFileName, TorrentJobPolicy.producedFile(work, "real.zim", "Display Name", byFileName::equals));
        // The torrent's file name is not always what landed; fall back to its display name.
        assertEquals(
                work.resolve("Display Name"),
                TorrentJobPolicy.producedFile(work, "real.zim", "Display Name", p -> false));
    }

    @Test
    void libtorrentsOwnVerdictIsEnough() {
        assertTrue(TorrentJobPolicy.complete(true, 0, 1000));
    }

    @Test
    void everyVerifiedByteAlsoCounts() {
        assertTrue(TorrentJobPolicy.complete(false, 1000, 1000));
        assertTrue(TorrentJobPolicy.complete(false, 1001, 1000));
        assertFalse(TorrentJobPolicy.complete(false, 999, 1000));
    }

    @Test
    void aTorrentOfUnknownSizeIsNeverInstantlyComplete() {
        // With total 0 the byte test would pass for any transfer at all, which is how a download
        // of nothing reaches verification and comes back "corrupt".
        assertFalse(TorrentJobPolicy.complete(false, 0, 0));
        assertFalse(TorrentJobPolicy.complete(false, 0, -1));
    }

    @Test
    void aStallIsJudgedOnBytesReceived() {
        long past = TorrentJobPolicy.STALL_TIMEOUT_NANOS + 1;
        assertTrue(TorrentJobPolicy.stalled(500, 500, past, false), "nothing new for longer than the timeout");
        assertFalse(TorrentJobPolicy.stalled(600, 500, past, false), "bytes arrived; not a stall");
    }

    @Test
    void aTransferInsideTheTimeoutIsNotAStall() {
        assertFalse(TorrentJobPolicy.stalled(500, 500, TorrentJobPolicy.STALL_TIMEOUT_NANOS - 1, false));
        assertFalse(TorrentJobPolicy.stalled(500, 500, 0, false));
    }

    @Test
    void aPausedTransferNeverStalls() {
        // It is not supposed to be receiving anything; failing it would punish the pause button.
        assertFalse(TorrentJobPolicy.stalled(500, 500, TorrentJobPolicy.STALL_TIMEOUT_NANOS * 10, true));
    }

    @Test
    void onlyADeliveredFileLetsTheWorkDirGo() {
        // Otherwise pressing Stop throws away the pieces that would have made the next attempt a
        // resume rather than a fresh multi-gigabyte fetch.
        assertTrue(TorrentJobPolicy.shouldCleanup(true));
        assertFalse(TorrentJobPolicy.shouldCleanup(false));
    }

    @Test
    void aSessionIsKeptOnlyWhileItIsSharingSomething() {
        assertTrue(TorrentJobPolicy.shouldStopSession(false, false), "no seed registered: a leaked session");
        assertFalse(TorrentJobPolicy.shouldStopSession(true, false), "sharing: leave it running");
        assertTrue(TorrentJobPolicy.shouldStopSession(true, true), "cancelled wins over sharing");
    }
}
