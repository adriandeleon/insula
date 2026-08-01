package com.insula.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Where the reader was in each article, and which articles were visited recently.
 *
 * <p>Returning to a long article and landing back at the top is one of the small frustrations that
 * makes an offline reader feel like a database browser. Positions are stored as a fraction of
 * document height rather than a pixel offset, so they survive a window resize or a font change.
 *
 * <p>Bounded on purpose: a reader accumulates positions indefinitely, and an unbounded file would
 * grow forever for no benefit. The oldest entries are dropped once {@link #MAX_ENTRIES} is
 * reached.
 */
public final class ReadingPositions {

    static final int MAX_ENTRIES = 2000;
    /** Below this the user has barely scrolled; restoring it would be noise. */
    static final double MIN_WORTH_SAVING = 0.02;

    private final Path file;

    /** Access-ordered so the eldest entry is the least recently *used*, not merely the oldest. */
    private final LinkedHashMap<String, Double> positions = new LinkedHashMap<>(64, 0.75f, true);

    private ReadingPositions(Path file) {
        this.file = file;
    }

    public static ReadingPositions load(Path file) {
        ReadingPositions store = new ReadingPositions(file);
        if (!Files.isRegularFile(file)) {
            return store;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return store; // unreadable: start clean rather than fail to open an archive
        }
        for (String key : props.stringPropertyNames()) {
            try {
                double value = Double.parseDouble(props.getProperty(key));
                if (value > 0 && value <= 1) {
                    store.positions.put(key, value);
                }
            } catch (NumberFormatException ignored) {
                // skip the malformed entry, keep the rest
            }
        }
        return store;
    }

    /** A stable key for an article: which archive, which entry. */
    public static String key(Path archive, String articlePath) {
        return archive.getFileName() + "|" + articlePath;
    }

    /** Records a position. A position at the very top is forgotten rather than stored. */
    public synchronized void remember(String key, double fraction) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (fraction < MIN_WORTH_SAVING) {
            positions.remove(key);
            return;
        }
        positions.put(key, Math.min(1, fraction));
        while (positions.size() > MAX_ENTRIES) {
            positions.remove(positions.keySet().iterator().next());
        }
    }

    /** The remembered position, or 0 when the article has not been read before. */
    public synchronized double positionOf(String key) {
        return positions.getOrDefault(key, 0.0);
    }

    public synchronized int size() {
        return positions.size();
    }

    /** Most recently read first. */
    public synchronized List<String> recent(int limit) {
        List<String> keys = List.copyOf(positions.keySet());
        return keys.reversed().stream().limit(limit).toList();
    }

    public synchronized void save() {
        Properties props = new Properties();
        Map<String, Double> snapshot = new LinkedHashMap<>(positions);
        snapshot.forEach((key, value) -> props.setProperty(key, String.valueOf(value)));
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "Insula reading positions");
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save reading positions to " + file, e);
        }
    }
}
