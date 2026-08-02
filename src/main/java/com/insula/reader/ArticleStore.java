package com.insula.reader;

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
import java.util.Properties;

/**
 * An ordered, bounded, persisted list of {@link ArticleRef}s — the storage behind both bookmarks
 * and history, which differ only in policy, not in shape.
 *
 * <p>Order is explicit rather than inherited from {@link Properties}, which is unordered: entries
 * are keyed by a zero-padded index so the file reads in order and reloads in order. Writes go
 * through a temp file and an atomic move, matching {@link ReadingPositions}, so a crash cannot
 * leave a half-written store.
 */
public class ArticleStore {

    private final Path file;
    private final int maxEntries;
    private final List<ArticleRef> entries = new ArrayList<>();

    protected ArticleStore(Path file, int maxEntries) {
        this.file = file;
        this.maxEntries = maxEntries;
    }

    public static ArticleStore load(Path file, int maxEntries) {
        ArticleStore store = new ArticleStore(file, maxEntries);
        store.read();
        return store;
    }

    protected final void read() {
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return; // an unreadable store is an empty one, not a failure to start
        }
        List<String> keys = new ArrayList<>(props.stringPropertyNames());
        keys.sort(Comparator.naturalOrder()); // zero-padded, so lexicographic is numeric
        for (String key : keys) {
            ArticleRef ref = ArticleRef.decode(props.getProperty(key));
            if (ref != null) {
                entries.add(ref);
            }
        }
        trim();
    }

    /** A copy, newest/first-ordered as stored. */
    public synchronized List<ArticleRef> entries() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean contains(ArticleRef ref) {
        return indexOf(ref) >= 0;
    }

    /** Puts a reference at the front, moving it there if it was already present. */
    public synchronized void addFirst(ArticleRef ref) {
        if (ref == null) {
            return;
        }
        int existing = indexOf(ref);
        if (existing >= 0) {
            entries.remove(existing);
        }
        entries.addFirst(ref);
        trim();
    }

    /** Appends, keeping an existing entry where it already sits. */
    public synchronized void addLast(ArticleRef ref) {
        if (ref == null || contains(ref)) {
            return;
        }
        entries.addLast(ref);
        trim();
    }

    public synchronized boolean remove(ArticleRef ref) {
        int index = indexOf(ref);
        if (index < 0) {
            return false;
        }
        entries.remove(index);
        return true;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private int indexOf(ArticleRef ref) {
        if (ref == null) {
            return -1;
        }
        String key = ref.key();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            entries.removeLast();
        }
    }

    public synchronized void save() {
        Properties props = new Properties();
        for (int i = 0; i < entries.size(); i++) {
            props.setProperty(String.format("%06d", i), entries.get(i).encode());
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Insula");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save " + file, e);
        }
    }
}
