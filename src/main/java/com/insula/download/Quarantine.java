package com.insula.download;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where a download goes when it fails verification.
 *
 * <p>The file is <b>moved aside, never deleted</b>. Throwing away 100 GB because of one bad piece
 * is hostile on the connections this app targets, and the bytes are still useful — a re-download
 * can reuse them, and the user may want to inspect or recover the file themselves.
 */
public final class Quarantine {

    public static final String SUFFIX = ".corrupt";

    private Quarantine() {}

    /**
     * Moves {@code file} to a sibling {@code <name>.corrupt} (numbered if that already exists) and
     * returns the new path. The {@code .part} resume bitmap goes with it, so a retry starts clean
     * rather than trusting a bitmap that describes bytes we just rejected.
     */
    public static Path quarantine(Path file) throws IOException {
        Path target = freeName(file);
        try {
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(file, target);
        }
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + HttpMultiSourceTransport.PART_SUFFIX));
        return target;
    }

    private static Path freeName(Path file) {
        Path candidate = file.resolveSibling(file.getFileName() + SUFFIX);
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = file.resolveSibling(file.getFileName() + SUFFIX + "." + n++);
        }
        return candidate;
    }
}
