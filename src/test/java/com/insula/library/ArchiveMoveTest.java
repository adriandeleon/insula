package com.insula.library;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Planning a folder change. These are the decisions that can lose someone's library. */
class ArchiveMoveTest {

    private static final Path OLD = Path.of("/home/x/.insula/archives");
    private static final Path NEW = Path.of("/mnt/big/archives");

    private static LibraryEntry entry(String path, long size) {
        return new LibraryEntry(Path.of(path), "Archive", size, "", true, 0);
    }

    @Test
    void archivesInTheOldFolderMove() {
        ArchiveMove.Plan plan =
                ArchiveMove.plan(List.of(entry(OLD + "/a.zim", 100), entry(OLD + "/b.zim", 200)), OLD, NEW, p -> false);
        assertEquals(2, plan.steps().size());
        assertEquals(300, plan.bytes(), "the size is what the user is agreeing to");
        assertEquals(Path.of(NEW + "/a.zim"), plan.steps().getFirst().to());
        assertTrue(plan.runnable());
    }

    @Test
    void anArchiveLivingElsewhereIsNotTheOldFoldersToMove() {
        // "Import into library" registers files where they are, so the index already spans folders.
        ArchiveMove.Plan plan =
                ArchiveMove.plan(List.of(entry("/media/usb/handed-to-me.zim", 100)), OLD, NEW, p -> false);
        assertTrue(plan.isEmpty());
        assertEquals(1, plan.staying());
    }

    @Test
    void aSiblingFolderWithASharedPrefixIsNotInsideTheOldOne() {
        // /home/x/.insula/archives2 must not be swept up by /home/x/.insula/archives.
        ArchiveMove.Plan plan = ArchiveMove.plan(List.of(entry(OLD + "2/a.zim", 100)), OLD, NEW, p -> false);
        assertTrue(plan.isEmpty());
        assertEquals(1, plan.staying());
    }

    @Test
    void aNameAlreadyTakenInTheDestinationIsRefusedRatherThanOverwritten() {
        // Two archives can legitimately share a file name across folders; replacing one destroys
        // a file nobody chose to lose.
        ArchiveMove.Plan plan = ArchiveMove.plan(
                List.of(entry(OLD + "/a.zim", 100), entry(OLD + "/b.zim", 200)),
                OLD,
                NEW,
                p -> p.equals(Path.of(NEW + "/a.zim")));
        assertFalse(plan.runnable(), "a plan with a conflict must not run");
        assertEquals(List.of("a.zim"), plan.conflicts());
    }

    @Test
    void choosingTheFolderItAlreadyUsesMovesNothing() {
        ArchiveMove.Plan plan = ArchiveMove.plan(List.of(entry(OLD + "/a.zim", 100)), OLD, OLD, p -> false);
        assertTrue(plan.isEmpty());
        assertTrue(plan.runnable(), "a no-op is not an error");
    }

    @Test
    void anUnnormalisedFolderStillMatches() {
        ArchiveMove.Plan plan =
                ArchiveMove.plan(List.of(entry(OLD + "/a.zim", 100)), Path.of(OLD + "/./"), NEW, p -> false);
        assertEquals(1, plan.steps().size());
    }

    @Test
    void anEmptyLibraryPlansNothingAndIsStillRunnable() {
        ArchiveMove.Plan plan = ArchiveMove.plan(List.of(), OLD, NEW, p -> false);
        assertTrue(plan.isEmpty());
        assertTrue(plan.runnable());
        assertEquals(0, plan.bytes());
    }
}
