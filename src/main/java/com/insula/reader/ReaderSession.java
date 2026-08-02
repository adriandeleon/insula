package com.insula.reader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The open tabs, remembered across restarts.
 *
 * <p>Reopening where you left off is the whole point: an offline reader is used in sittings, and
 * losing five open articles to a restart is the kind of small loss that makes a tool feel careless.
 * The session is deliberately <em>only</em> the tabs — the scroll inside each one is
 * {@link ReadingPositions}' job, and duplicating it here would give two stores an opinion about the
 * same number.
 *
 * <p>Two rules the restore obeys, both of which exist because an archive can vanish between
 * sittings (deleted, moved, on a drive that is not plugged in):
 *
 * <ul>
 *   <li><b>A tab whose archive is gone is dropped, not restored broken.</b> Restoring it would put
 *       an unopenable tab in the strip with no way to tell why.
 *   <li><b>The active index is re-derived, never trusted.</b> If tabs ahead of it were dropped, a
 *       stored index points at the wrong article — or off the end.
 * </ul>
 */
public final class ReaderSession {

    /** One remembered tab. */
    public record Entry(ArticleRef article, boolean active) {}

    /** A ceiling, so a runaway session file cannot make startup crawl. */
    static final int MAX_TABS = 50;

    private static final String ACTIVE_MARKER = "*";

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    private ReaderSession(Path file) {
        this.file = file;
    }

    public static ReaderSession load(Path file) {
        ReaderSession session = new ReaderSession(file);
        if (!Files.isRegularFile(file)) {
            return session;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || session.entries.size() >= MAX_TABS) {
                    continue;
                }
                boolean active = line.startsWith(ACTIVE_MARKER);
                ArticleRef ref = ArticleRef.decode(active ? line.substring(1) : line);
                if (ref != null) {
                    session.entries.add(new Entry(ref, active));
                }
            }
        } catch (IOException | RuntimeException e) {
            // A damaged session is not worth failing a launch over; start with no tabs.
            session.entries.clear();
        }
        return session;
    }

    /** Replaces the remembered set. {@code activeIndex} may be -1 when nothing is open. */
    public void set(List<ArticleRef> open, int activeIndex) {
        entries.clear();
        for (int i = 0; i < open.size() && i < MAX_TABS; i++) {
            entries.add(new Entry(open.get(i), i == activeIndex));
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * The tabs worth reopening, in order, with the index to select — computed from the survivors,
     * so dropping a missing archive can never leave the selection pointing at the wrong article.
     */
    public Restored restore(java.util.function.Predicate<Path> archiveExists) {
        List<ArticleRef> open = new ArrayList<>();
        int active = -1;
        for (Entry entry : entries) {
            if (!archiveExists.test(entry.article().archiveFile())) {
                continue;
            }
            if (entry.active()) {
                active = open.size();
            }
            open.add(entry.article());
        }
        if (active < 0 && !open.isEmpty()) {
            active = 0;
        }
        return new Restored(List.copyOf(open), active, entries.size() - open.size());
    }

    /** @param dropped how many tabs referred to an archive that is no longer there */
    public record Restored(List<ArticleRef> open, int activeIndex, int dropped) {}

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                lines.add(
                        (entry.active() ? ACTIVE_MARKER : "") + entry.article().encode());
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Losing the session is survivable; failing a save or a quit over it is not.
        }
    }
}
