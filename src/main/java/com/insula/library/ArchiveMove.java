package com.insula.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Planning a change of archives folder.
 *
 * <p>Pure, because the decisions here are the ones that can lose someone's library. The plan is
 * computed and checked before a single byte moves, and it encodes three rules:
 *
 * <ul>
 *   <li><b>Only archives that live in the old folder move.</b> The library index holds absolute
 *       paths and already spans folders — "Import into library" registers files where they are —
 *       so an archive somewhere else entirely is not the old folder's to relocate.
 *   <li><b>A name already taken in the destination is refused, not overwritten.</b> Two archives
 *       can legitimately share a file name across folders, and silently replacing one with the
 *       other destroys a file the user never chose to lose.
 *   <li><b>Nothing moves onto itself.</b> Choosing the folder it already uses is a no-op, not a
 *       delete-and-copy.
 * </ul>
 */
public final class ArchiveMove {

    /** One file to relocate. */
    public record Step(LibraryEntry entry, Path from, Path to) {}

    /**
     * @param steps files that will move, in index order
     * @param bytes total size of those files, for telling the user what they are agreeing to
     * @param staying archives left where they are because they live outside the old folder
     * @param conflicts names already present in the destination; a plan with any is not runnable
     */
    public record Plan(List<Step> steps, long bytes, int staying, List<String> conflicts) {

        public boolean runnable() {
            return conflicts.isEmpty();
        }

        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    private ArchiveMove() {}

    /**
     * Works out what moving from {@code oldFolder} to {@code newFolder} would involve.
     *
     * @param exists whether a path is already present in the destination — injected so the whole
     *     plan stays testable without a filesystem
     */
    public static Plan plan(
            List<LibraryEntry> entries, Path oldFolder, Path newFolder, java.util.function.Predicate<Path> exists) {
        List<Step> steps = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        long bytes = 0;
        int staying = 0;

        Path from = oldFolder.toAbsolutePath().normalize();
        Path to = newFolder.toAbsolutePath().normalize();
        if (from.equals(to)) {
            return new Plan(List.of(), 0, entries.size(), List.of());
        }

        for (LibraryEntry entry : entries) {
            Path file = entry.file().toAbsolutePath().normalize();
            // Compared on path components, never string prefixes: /data/archives2 must not count
            // as living inside /data/archives.
            if (!file.startsWith(from)) {
                staying++;
                continue;
            }
            Path target = to.resolve(from.relativize(file));
            if (exists.test(target)) {
                conflicts.add(entry.fileName());
                continue;
            }
            steps.add(new Step(entry, file, target));
            bytes += Math.max(0, entry.sizeBytes());
        }
        return new Plan(List.copyOf(steps), bytes, staying, List.copyOf(conflicts));
    }
}
