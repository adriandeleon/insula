package com.insula.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.zim.ZimArchive;

/**
 * One search box across every archive on disk.
 *
 * <p>This is the feature Kiwix has no answer for: asking a question without first deciding which
 * book it lives in. Results from Wikipedia, a Stack Exchange dump and a wiki are merged and ranked
 * together, each labelled with the archive it came from.
 *
 * <p>Indexing is lazy and off-thread — an archive is walked the first time it is searched, not
 * when it is opened, so adding a large archive to the library never blocks anything. Until its
 * index is ready an archive simply contributes no results, and the next keystroke includes it.
 */
public final class LibrarySearch implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LibrarySearch.class.getName());

    /** A result plus which archive it came from. */
    public record Result(String archiveTitle, Path archiveFile, String title, String fullPath, int score) {}

    private record Source(Path file, String title, ZimArchive archive) {}

    private final Map<Path, TitleIndex> indexes = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> indexing = new ConcurrentHashMap<>();
    private final List<Source> sources = new ArrayList<>();

    private final AtomicLong generation = new AtomicLong();

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "library-search");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService indexExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "library-index");
        t.setDaemon(true);
        return t;
    });

    /** Registers an archive to search. The caller retains ownership of the {@link ZimArchive}. */
    public synchronized void add(Path file, String archiveTitle, ZimArchive archive) {
        sources.removeIf(s -> s.file().equals(file));
        sources.add(new Source(file, archiveTitle, archive));
    }

    public synchronized void remove(Path file) {
        sources.removeIf(s -> s.file().equals(file));
        indexes.remove(file);
        indexing.remove(file);
    }

    public synchronized List<Path> archives() {
        return sources.stream().map(Source::file).toList();
    }

    /** Archives whose index is built and therefore contributing results. */
    public int readyCount() {
        return indexes.size();
    }

    /**
     * Runs a search and delivers ranked results on the search thread. Superseded queries are
     * dropped, so a fast typist never sees older results overwrite newer ones.
     */
    public void search(String query, int limit, Consumer<List<Result>> onResults) {
        long stamp = generation.incrementAndGet();
        searchExecutor.execute(() -> {
            List<Result> results = searchNow(query, limit);
            if (stamp == generation.get()) {
                onResults.accept(results);
            }
        });
    }

    /** Synchronous search over whatever is indexed right now; also kicks off missing indexes. */
    public List<Result> searchNow(String query, int limit) {
        String lowered = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (lowered.isEmpty()) {
            return List.of();
        }
        List<Source> current;
        synchronized (this) {
            current = List.copyOf(sources);
        }

        List<Result> merged = new ArrayList<>();
        for (Source source : current) {
            TitleIndex index = indexes.get(source.file());
            if (index == null) {
                ensureIndexed(source);
                continue;
            }
            for (TitleIndex.Hit hit : index.search(lowered, limit)) {
                merged.add(new Result(source.title(), source.file(), hit.title(), hit.fullPath(), hit.score()));
            }
        }
        merged.sort(Comparator.comparingInt(Result::score)
                .reversed()
                .thenComparing(Result::title, Comparator.naturalOrder()));
        return merged.size() > limit ? List.copyOf(merged.subList(0, limit)) : List.copyOf(merged);
    }

    /** Builds an archive's index once, in the background. */
    private void ensureIndexed(Source source) {
        if (indexing.putIfAbsent(source.file(), Boolean.TRUE) != null) {
            return;
        }
        indexExecutor.execute(() -> {
            try {
                long start = System.nanoTime();
                TitleIndex index = TitleIndex.build(source.archive());
                indexes.put(source.file(), index);
                LOG.fine(() -> "Indexed " + index.size() + " titles from "
                        + source.file().getFileName() + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not index " + source.file(), e);
                indexing.remove(source.file()); // allow a retry
            }
        });
    }

    /** Test seam: block until every registered archive is indexed. */
    public void awaitIndexed(java.time.Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<Source> current;
            synchronized (this) {
                current = List.copyOf(sources);
            }
            current.forEach(this::ensureIndexed);
            if (current.stream().allMatch(s -> indexes.containsKey(s.file()))) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Override
    public void close() {
        searchExecutor.shutdownNow();
        indexExecutor.shutdownNow();
        indexes.clear();
    }
}
