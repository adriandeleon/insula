package com.offlinewiki.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * The set of archives on this machine, persisted as a properties file beside the settings.
 *
 * <p>Entries are keyed by index ({@code 1.path}, {@code 1.verified}, …). A row whose file has
 * since been deleted is dropped on load, so the library never offers something that is not there.
 */
public final class Library {

    private final Path file;
    private final List<LibraryEntry> entries = new ArrayList<>();

    private Library(Path file) {
        this.file = file;
    }

    public static Library load(Path file) {
        Library library = new Library(file);
        if (!Files.isRegularFile(file)) {
            return library;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return library; // unreadable index: start empty rather than fail to launch
        }
        for (int i = 1; ; i++) {
            String path = props.getProperty(i + ".path");
            if (path == null) {
                break;
            }
            Path archive = Path.of(path);
            if (!Files.isRegularFile(archive)) {
                continue; // deleted outside the app
            }
            library.entries.add(new LibraryEntry(
                    archive,
                    props.getProperty(i + ".title", archive.getFileName().toString()),
                    parseLong(props.getProperty(i + ".size")),
                    props.getProperty(i + ".sha256", ""),
                    Boolean.parseBoolean(props.getProperty(i + ".verified", "false")),
                    parseLong(props.getProperty(i + ".added"))));
        }
        return library;
    }

    public void save() {
        Properties props = new Properties();
        for (int i = 0; i < entries.size(); i++) {
            LibraryEntry e = entries.get(i);
            String key = (i + 1) + ".";
            props.setProperty(key + "path", e.file().toAbsolutePath().toString());
            props.setProperty(key + "title", e.title());
            props.setProperty(key + "size", String.valueOf(e.sizeBytes()));
            props.setProperty(key + "sha256", e.sha256());
            props.setProperty(key + "verified", String.valueOf(e.verified()));
            props.setProperty(key + "added", String.valueOf(e.addedAtEpochMs()));
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Offline Wiki library");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save library to " + file, e);
        }
    }

    /** Adds or replaces the entry for this file (matched by absolute path). */
    public void put(LibraryEntry entry) {
        remove(entry.file());
        entries.add(entry);
    }

    public void remove(Path archive) {
        Path target = archive.toAbsolutePath();
        entries.removeIf(e -> e.file().toAbsolutePath().equals(target));
    }

    public Optional<LibraryEntry> find(Path archive) {
        Path target = archive.toAbsolutePath();
        return entries.stream()
                .filter(e -> e.file().toAbsolutePath().equals(target))
                .findFirst();
    }

    /** Most recently added first. */
    public List<LibraryEntry> entries() {
        return entries.stream()
                .sorted(Comparator.comparingLong(LibraryEntry::addedAtEpochMs).reversed())
                .toList();
    }

    /** Only these may be opened automatically. */
    public List<LibraryEntry> verifiedEntries() {
        return entries().stream().filter(LibraryEntry::verified).toList();
    }

    public boolean isVerified(Path archive) {
        return find(archive).map(LibraryEntry::verified).orElse(false);
    }

    public int size() {
        return entries.size();
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
