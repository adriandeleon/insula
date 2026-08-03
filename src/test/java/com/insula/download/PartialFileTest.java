package com.insula.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What may be believed about bytes already on disk. */
class PartialFileTest {

    private static final long SIZE = 10_752_256_261L;

    @Test
    void aFullLengthFileWithNoResumeRecordIsAHuskAndMustBeRestarted() {
        // The measured bug: a torrent stopped at 4% left a full-length sparse file, so the
        // "is it already long enough?" test said yes and ten gigabytes of holes were verified.
        assertTrue(PartialFile.mustRestart(SIZE, SIZE, false));
        assertFalse(PartialFile.resumable(SIZE, SIZE, false));
    }

    @Test
    void aShorterFileWithNoResumeRecordIsAlsoNotResumable() {
        assertTrue(PartialFile.mustRestart(417_000_000L, SIZE, false));
    }

    @Test
    void aResumeRecordIsWhatMakesBytesBelievable() {
        assertTrue(PartialFile.resumable(417_000_000L, SIZE, true));
        assertFalse(PartialFile.mustRestart(417_000_000L, SIZE, true));
    }

    @Test
    void nothingOnDiskNeedsNoRestart() {
        assertFalse(PartialFile.mustRestart(-1, SIZE, false));
        assertFalse(PartialFile.mustRestart(0, SIZE, false));
    }

    @Test
    void aFileLongerThanPublishedCannotBeThisDownload() {
        assertTrue(PartialFile.mustRestart(SIZE + 1, SIZE, true));
    }
}
