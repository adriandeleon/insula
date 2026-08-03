package com.insula.fulltext;

import java.nio.file.Path;
import java.util.HexFormat;

/**
 * Where an archive's index lives.
 *
 * <p>Keyed on the archive's own UUID rather than its file name or its path, which is what makes
 * the answer to "is this indexed?" survive the things that happen to files. Renaming an archive,
 * or moving it to another disk, keeps its index; and — the case that matters more — a <em>new
 * edition</em> of the same archive carries a different UUID, so it gets a different index rather
 * than silently inheriting one built from last year's text.
 *
 * <p>Pure, so the naming can be pinned without a filesystem.
 */
public final class IndexPaths {

    private IndexPaths() {}

    /** The folder holding every index. */
    public static Path root(Path configDir) {
        return configDir.resolve("fulltext");
    }

    /**
     * The folder for one archive's index.
     *
     * @param uuid the 16 bytes from the ZIM header
     */
    public static Path forArchive(Path configDir, byte[] uuid) {
        return root(configDir).resolve(key(uuid));
    }

    /**
     * The archive's UUID as a directory name.
     *
     * <p>Hex, so it is filesystem-safe everywhere without any escaping to get wrong. An archive
     * whose header carries no UUID — all zeroes, which some older files do — still gets a stable
     * name, and shares it with every other such archive; that is a real collision, but the
     * alternative is refusing to index those files at all.
     */
    public static String key(byte[] uuid) {
        if (uuid == null || uuid.length == 0) {
            return "no-uuid";
        }
        return HexFormat.of().formatHex(uuid);
    }
}
