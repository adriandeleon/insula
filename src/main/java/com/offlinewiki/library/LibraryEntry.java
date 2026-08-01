package com.offlinewiki.library;

import java.nio.file.Path;

/**
 * A local archive the app knows about.
 *
 * <p>{@code verified} is the load-bearing field: only a verified archive may be opened
 * automatically. A downloaded-but-unverified or quarantined file stays listed (so the user can see
 * it and retry) but is never silently reopened at startup.
 */
public record LibraryEntry(
        Path file, String title, long sizeBytes, String sha256, boolean verified, long addedAtEpochMs) {

    public String fileName() {
        return file.getFileName().toString();
    }

    public LibraryEntry withVerified(boolean value) {
        return new LibraryEntry(file, title, sizeBytes, sha256, value, addedAtEpochMs);
    }
}
