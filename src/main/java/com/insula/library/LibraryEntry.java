package com.insula.library;

import java.nio.file.Path;

/**
 * A local archive the app knows about.
 *
 * <p>{@code verified} is the load-bearing field: only a verified archive may be opened
 * automatically. A downloaded-but-unverified or quarantined file stays listed (so the user can see
 * it and retry) but is never silently reopened at startup.
 *
 * <p>The last three fields are the shelf's organisation, which belongs to the reader rather than
 * to the catalog: {@code pinned} lifts an archive above every grouping, {@code order} is the
 * hand-dragged position used when sorting is Custom, and {@code theme} overrides the catalog's own
 * category for this one archive. All three persist, so a rearrangement is never quietly lost.
 */
public record LibraryEntry(
        Path file,
        String title,
        long sizeBytes,
        String sha256,
        boolean verified,
        long addedAtEpochMs,
        boolean pinned,
        int order,
        String theme) {

    public LibraryEntry {
        theme = theme == null ? "" : theme;
    }

    /** The shape callers used before the shelf could be organised; unpinned, unordered, unthemed. */
    public LibraryEntry(Path file, String title, long sizeBytes, String sha256, boolean verified, long addedAtEpochMs) {
        this(file, title, sizeBytes, sha256, verified, addedAtEpochMs, false, 0, "");
    }

    public String fileName() {
        return file.getFileName().toString();
    }

    public LibraryEntry withVerified(boolean value) {
        return new LibraryEntry(file, title, sizeBytes, sha256, value, addedAtEpochMs, pinned, order, theme);
    }

    public LibraryEntry withPinned(boolean value) {
        return new LibraryEntry(file, title, sizeBytes, sha256, verified, addedAtEpochMs, value, order, theme);
    }

    public LibraryEntry withOrder(int value) {
        return new LibraryEntry(file, title, sizeBytes, sha256, verified, addedAtEpochMs, pinned, value, theme);
    }

    public LibraryEntry withTheme(String value) {
        return new LibraryEntry(file, title, sizeBytes, sha256, verified, addedAtEpochMs, pinned, order, value);
    }
}
