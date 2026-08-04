package com.insula.fulltext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.zim.ZimArchive;

/**
 * Full-text search across the library: which archives have an index, building the ones that do
 * not, and searching the ones that do.
 *
 * <p>No JavaFX here, like {@link com.insula.search.LibrarySearch} — callbacks arrive on this
 * service's own threads and the caller marshals them. Keeping the toolkit out is what lets the
 * indexing pass be exercised without a window.
 */
public final class FullTextService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FullTextService.class.getName());

    /** Whether an archive can be searched by its text, and if not, why not. */
    public enum State {
        /** No index has been built. */
        ABSENT,
        /** Being built now. */
        BUILDING,
        /** Ready to search. */
        READY
    }

    /** How an indexing job reports itself. */
    public record Progress(String archiveTitle, long done, long total, long indexed) {

        public double fraction() {
            return total <= 0 ? -1 : (double) done / total;
        }
    }

    /** One result, in the shape the results list already understands. */
    public record Hit(Path archiveFile, String archiveTitle, String title, String path, float score) {}

    private record Source(Path file, String title, ZimArchive archive, Path indexDir) {}

    private final Path configDir;
    private final Map<Path, Source> sources = new ConcurrentHashMap<>();
    private final Map<Path, FullTextIndex> open = new ConcurrentHashMap<>();

    private final ExecutorService searchPool = Executors.newSingleThreadExecutor(daemon("fulltext-search"));
    private final ExecutorService indexPool = Executors.newSingleThreadExecutor(daemon("fulltext-index"));
    private final AtomicLong searchGeneration = new AtomicLong();

    private volatile Path building;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public FullTextService(Path configDir) {
        this.configDir = configDir;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Registers an archive so it can be indexed and searched.
     *
     * <p>Takes an already-open archive rather than a path: the reader has one open anyway, and its
     * UUID — which is what names the index — is only readable from inside it.
     */
    public void register(Path file, String title, ZimArchive archive) {
        Path key = file.toAbsolutePath();
        Path indexDir = IndexPaths.forArchive(configDir, archive.header().uuid());
        sources.put(key, new Source(key, title, archive, indexDir));
    }

    public void unregister(Path file) {
        Path key = file.toAbsolutePath();
        sources.remove(key);
        closeIndex(key);
    }

    /** Where an archive stands. */
    public State state(Path file) {
        Path key = file.toAbsolutePath();
        if (key.equals(building)) {
            return State.BUILDING;
        }
        Source source = sources.get(key);
        if (source == null) {
            return State.ABSENT;
        }
        return Files.isDirectory(source.indexDir()) ? State.READY : State.ABSENT;
    }

    /** Archives that are registered but have no index yet. */
    public List<Path> unindexed() {
        return sources.keySet().stream().filter(f -> state(f) == State.ABSENT).toList();
    }

    /** What an archive's index occupies on disk, or 0. */
    public long indexBytes(Path file) {
        Source source = sources.get(file.toAbsolutePath());
        return source == null ? 0 : directoryBytes(source.indexDir());
    }

    /** What every index occupies, including any belonging to archives no longer in the library. */
    public long totalIndexBytes() {
        return directoryBytes(IndexPaths.root(configDir));
    }

    private static long directoryBytes(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Builds an archive's index.
     *
     * <p>One at a time. Two passes at once would fight over the same cores and finish both later
     * than running them in turn, and the reader is usually waiting on the first.
     *
     * @param onProgress called repeatedly on the indexing thread
     * @param onDone called once, with whether an index now exists
     */
    public void buildIndex(Path file, Consumer<Progress> onProgress, Consumer<Boolean> onDone) {
        Path key = file.toAbsolutePath();
        Source source = sources.get(key);
        if (source == null || state(key) != State.ABSENT) {
            onDone.accept(state(key) == State.READY);
            return;
        }
        cancelled.set(false);
        building = key;
        indexPool.execute(() -> {
            boolean ok = false;
            try {
                closeIndex(key); // a reader open on the old index would hold its files
                ArchiveIndexer.Result result = ArchiveIndexer.index(
                        source.archive(),
                        source.indexDir(),
                        (done, total, indexed) -> onProgress.accept(new Progress(source.title(), done, total, indexed)),
                        cancelled::get);
                ok = !result.cancelled();
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not index " + source.title(), e);
            } finally {
                building = null;
                onDone.accept(ok);
            }
        });
    }

    /** Stops the build in progress. What it had written is discarded, not left half-done. */
    public void cancelIndexing() {
        cancelled.set(true);
    }

    public boolean isIndexing() {
        return building != null;
    }

    /**
     * Searches every indexed archive.
     *
     * <p>Generation-guarded like the title search: a result from a query the user has already
     * typed past is dropped rather than shown, which is what stops the list flickering between
     * answers as somebody types.
     */
    public void search(String query, int limit, Consumer<List<Hit>> onResults) {
        long generation = searchGeneration.incrementAndGet();
        searchPool.execute(() -> {
            List<Hit> hits = collect(query, limit);
            if (generation == searchGeneration.get()) {
                onResults.accept(hits);
            }
        });
    }

    private List<Hit> collect(String query, int limit) {
        List<Hit> hits = new ArrayList<>();
        for (Source source : sources.values()) {
            if (state(source.file()) != State.READY) {
                continue;
            }
            try {
                FullTextIndex index = indexFor(source);
                for (FullTextIndex.Hit hit : index.search(query, limit)) {
                    hits.add(new Hit(source.file(), source.title(), hit.title(), hit.path(), hit.score()));
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not search " + source.title(), e);
            }
        }
        // Scores are comparable within an index but not across them, so this orders by relevance
        // inside each archive and interleaves the rest — good enough to put the strongest answers
        // near the top without pretending two archives' scores mean the same thing.
        hits.sort((a, b) -> Float.compare(b.score(), a.score()));
        return hits.size() > limit ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);
    }

    private FullTextIndex indexFor(Source source) throws IOException {
        FullTextIndex existing = open.get(source.file());
        if (existing != null) {
            return existing;
        }
        FullTextIndex opened = FullTextIndex.open(source.indexDir());
        FullTextIndex raced = open.putIfAbsent(source.file(), opened);
        if (raced != null) {
            opened.close();
            return raced;
        }
        return opened;
    }

    /** Deletes an archive's index. */
    public boolean deleteIndex(Path file) {
        Path key = file.toAbsolutePath();
        Source source = sources.get(key);
        if (source == null) {
            return false;
        }
        closeIndex(key);
        try (var paths = Files.walk(source.indexDir())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            return true;
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not delete the index for " + source.title(), e);
            return false;
        }
    }

    private void closeIndex(Path key) {
        FullTextIndex index = open.remove(key);
        if (index != null) {
            try {
                index.close();
            } catch (IOException ignored) {
                // closing a read-only index; nothing actionable
            }
        }
    }

    @Override
    public void close() {
        cancelIndexing();
        searchPool.shutdownNow();
        indexPool.shutdownNow();
        open.keySet().forEach(this::closeIndex);
        sources.clear();
    }
}
