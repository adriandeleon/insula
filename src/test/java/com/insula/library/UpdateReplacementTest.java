package com.insula.library;

import java.nio.file.Path;

import com.insula.library.UpdateReplacement.Policy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting a superseded archive is irreversible and offline, so every guard is pinned. */
class UpdateReplacementTest {

    private static final Path OLD = Path.of("/lib/wikipedia_en_2025-01.zim");
    private static final Path NEW = Path.of("/lib/wikipedia_en_2026-01.zim");

    @Test
    void replaceDeletesTheOldBuildOnceTheNewOneIsVerified() {
        assertTrue(UpdateReplacement.shouldDelete(Policy.REPLACE, OLD, NEW, true));
    }

    @Test
    void anUnverifiedReplacementNeverCostsTheWorkingCopy() {
        // Trading a good archive for a corrupt one is the failure that cannot be undone offline.
        assertFalse(UpdateReplacement.shouldDelete(Policy.REPLACE, OLD, NEW, false));
    }

    @Test
    void keepLeavesBothBuildsAlone() {
        assertFalse(UpdateReplacement.shouldDelete(Policy.KEEP, OLD, NEW, true));
    }

    @Test
    void aBuildThatReusesItsFileNameDoesNotDeleteWhatWasJustInstalled() {
        assertFalse(UpdateReplacement.shouldDelete(Policy.REPLACE, NEW, NEW, true));
        assertFalse(
                UpdateReplacement.shouldDelete(Policy.REPLACE, Path.of("/lib/./a.zim"), Path.of("/lib/a.zim"), true));
    }

    @Test
    void aMissingSideOfThePairIsNeverActedOn() {
        assertFalse(UpdateReplacement.shouldDelete(Policy.REPLACE, null, NEW, true));
        assertFalse(UpdateReplacement.shouldDelete(Policy.REPLACE, OLD, null, true));
    }

    @Test
    void askAlsoClearsTheDeleteButLeavesTheConfirmToTheCaller() {
        assertTrue(UpdateReplacement.shouldDelete(Policy.ASK, OLD, NEW, true));
        assertTrue(Policy.ASK.confirms());
        assertFalse(Policy.REPLACE.confirms());
        assertFalse(Policy.KEEP.confirms());
    }

    @Test
    void thePolicyRoundTripsThroughItsStoredForm() {
        for (Policy policy : Policy.values()) {
            assertEquals(policy, Policy.parse(policy.stored()));
        }
        // Anything unrecognised — including a config written by a newer build — means asking,
        // which is the only option that cannot destroy something on its own.
        assertEquals(Policy.ASK, Policy.parse("nonsense"));
        assertEquals(Policy.ASK, Policy.parse(null));
    }
}
