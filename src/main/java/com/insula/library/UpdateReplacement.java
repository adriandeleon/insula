package com.insula.library;

import java.nio.file.Path;

/**
 * Whether a freshly-verified archive should displace the one it updates.
 *
 * <p>Archives are measured in gigabytes, so "Update" quietly meaning "download a second copy and
 * keep both" doubles the disk cost of a word that promises the opposite. But deleting the old file
 * is irreversible and offline, where the replacement cannot simply be fetched again, so the
 * decision is fenced by conditions rather than taken on trust:
 *
 * <ul>
 *   <li><b>Only after the replacement is verified and in the library.</b> Deleting on download
 *       start — or on a merely-complete download — risks destroying the working copy in exchange
 *       for a corrupt one.
 *   <li><b>Never when the two resolve to the same file.</b> A build that reuses its file name would
 *       otherwise delete what was just installed.
 *   <li><b>Never when the old file is not actually the thing being replaced.</b> The caller records
 *       the pair; a mismatch means the pairing is stale and the safe move is to keep both.
 * </ul>
 */
public final class UpdateReplacement {

    /**
     * What to do with the superseded archive once its replacement is safely in the library.
     *
     * <p>{@link #ASK} is the default rather than {@link #REPLACE} because the supersede test is
     * name-based and can be wrong; the confirmation is the only thing standing between a false
     * positive and a deleted archive that cannot be re-fetched offline. The other two exist so
     * someone who has decided is not asked forever.
     */
    public enum Policy {
        /** Confirm each time, naming the files and the space. Today's behaviour, and the default. */
        ASK,
        /** Delete the old build without asking. */
        REPLACE,
        /** Keep both builds, and stop asking. */
        KEEP;

        public static Policy parse(String raw) {
            if (raw == null) {
                return ASK;
            }
            return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
                case "replace" -> REPLACE;
                case "keep" -> KEEP;
                default -> ASK;
            };
        }

        public String stored() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Whether the user should be prompted before anything is deleted. */
        public boolean confirms() {
            return this == ASK;
        }
    }

    private UpdateReplacement() {}

    /**
     * @param policy the user's choice
     * @param oldFile the archive being replaced, as recorded when the update was started
     * @param newFile the replacement, now verified and in the library
     * @param newVerified whether the replacement passed its checksum
     * @return true when {@code oldFile} may be deleted — subject, under {@link Policy#ASK}, to the
     *     caller still getting a yes
     */
    public static boolean shouldDelete(Policy policy, Path oldFile, Path newFile, boolean newVerified) {
        if (policy == Policy.KEEP || !newVerified || oldFile == null || newFile == null) {
            return false;
        }
        return !oldFile.toAbsolutePath()
                .normalize()
                .equals(newFile.toAbsolutePath().normalize());
    }
}
