package com.insula.fulltext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;

/**
 * Reads every article out of an archive and puts it in an index.
 *
 * <p>The expensive half of full-text search: one pass over the whole file, decompressing every
 * cluster it touches. On a ten-gigabyte archive that is minutes, not seconds, which is why nothing
 * here decides to run — it is started deliberately, reports where it has got to, and stops when
 * asked.
 */
public final class ArchiveIndexer {

    private static final Logger LOG = Logger.getLogger(ArchiveIndexer.class.getName());

    /**
     * Beyond this a single "article" is not prose — a generated data dump, or a page whose whole
     * body is one enormous table. Indexing it costs far more than anyone will ever get back.
     */
    private static final int MAX_ARTICLE_BYTES = 8 * 1024 * 1024;

    /** How the caller is told where the pass has got to. */
    @FunctionalInterface
    public interface Progress {
        /**
         * @param scanned articles handled so far
         * @param total articles this archive will contribute — not its entry count, which is
         *     mostly images and would leave a bar that races to a third and then crawls
         * @param indexed how many of them had text worth indexing
         */
        void at(long scanned, long total, long indexed);
    }

    /** What a finished pass produced. */
    public record Result(long indexed, long skipped, boolean cancelled) {}

    private ArchiveIndexer() {}

    /**
     * Indexes {@code archive} into {@code indexDir}.
     *
     * <p>Builds into a temporary folder and moves it into place at the end, so an interrupted run
     * leaves the previous index — or no index — rather than a half-built one. An index that
     * answers incompletely is worse than one that admits it is not there, because nothing
     * downstream can tell the difference.
     *
     * @param cancelled polled between articles; a pass that is stopped leaves no index behind
     */
    public static Result index(ZimArchive archive, Path indexDir, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        Path staging = indexDir.resolveSibling(indexDir.getFileName() + ".building");
        deleteTree(staging);
        Files.createDirectories(staging);

        long[] order = articleOrder(archive);
        Counts counts = new Counts();
        counts.skipped = archive.entryCount() - order.length;
        boolean stopped;

        try (FullTextIndex.Builder builder = FullTextIndex.builder(staging)) {
            stopped = run(archive, order, builder, counts, progress, cancelled);
        }

        long indexed = counts.indexed.get();
        long skipped = counts.skipped + counts.failed.get();

        if (stopped) {
            deleteTree(staging);
            return new Result(indexed, skipped, true);
        }
        deleteTree(indexDir);
        Files.createDirectories(indexDir.getParent());
        Files.move(staging, indexDir);
        return new Result(indexed, skipped, false);
    }

    /** One article on its way from the archive to the index. */
    private record Article(String path, String title, byte[] html) {}

    /** Shared tallies. The counters are touched by every worker; the survey figure is not. */
    private static final class Counts {
        final java.util.concurrent.atomic.AtomicLong indexed = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong failed = new java.util.concurrent.atomic.AtomicLong();
        long skipped;
    }

    /** Stops the workers without a special case for "was there an article or not". */
    private static final Article POISON = new Article("", "", new byte[0]);

    /**
     * How many articles may be waiting between the reader and the workers.
     *
     * <p>Small on purpose. The queue exists to keep the workers fed, not to buffer the archive:
     * articles are held decompressed, and a generous queue on a file with large pages is hundreds
     * of megabytes of nothing useful.
     */
    private static final int QUEUE_DEPTH = 32;

    /**
     * Runs the pass with one reader and a pool of workers.
     *
     * <p>Everything that touches the archive stays on the calling thread. {@code ZimArchive} keeps
     * a cluster cache in a plain LinkedHashMap and reads through one file channel — it is not
     * thread-safe, and sharing it would corrupt the cache rather than fail loudly. So the reader
     * decompresses in cluster order, exactly as before, and the workers take it from there.
     *
     * <p>That split is also where the work actually is now: with cluster ordering the
     * decompression is a small fraction of the pass, and turning HTML into text and handing it to
     * Lucene is the rest. Both are pure CPU, and {@code IndexWriter} is thread-safe.
     *
     * @return whether the pass was cancelled
     */
    private static boolean run(
            ZimArchive archive,
            long[] order,
            FullTextIndex.Builder builder,
            Counts counts,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {

        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        java.util.concurrent.BlockingQueue<Article> queue = new java.util.concurrent.ArrayBlockingQueue<>(QUEUE_DEPTH);
        java.util.List<Thread> pool = new java.util.ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            Thread t = new Thread(() -> consume(queue, builder, counts), "fulltext-index-" + w);
            t.setDaemon(true);
            // Indexing is something the reader started and is waiting on, but it must not make
            // the rest of the app unresponsive while it runs.
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
            pool.add(t);
        }

        boolean stopped = false;
        try {
            for (int at = 0; at < order.length; at++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    stopped = true;
                    break;
                }
                if (progress != null && (at % 512 == 0 || at == order.length - 1)) {
                    progress.at(at + 1, order.length, counts.indexed.get());
                }
                Article article = read(archive, (int) (order[at] & 0xFFFFFFFFL));
                if (article == null) {
                    counts.failed.incrementAndGet();
                    continue;
                }
                queue.put(article);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        } finally {
            drain(queue, pool);
        }
        return stopped;
    }

    /** Feeds every worker a poison pill and waits for them, so the builder closes on a quiet index. */
    private static void drain(java.util.concurrent.BlockingQueue<Article> queue, java.util.List<Thread> pool) {
        try {
            for (int i = 0; i < pool.size(); i++) {
                queue.put(POISON);
            }
            for (Thread t : pool) {
                t.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.forEach(Thread::interrupt);
        }
    }

    private static void consume(
            java.util.concurrent.BlockingQueue<Article> queue, FullTextIndex.Builder builder, Counts counts) {
        try {
            while (true) {
                Article article = queue.take();
                if (article == POISON) {
                    return;
                }
                try {
                    String text = HtmlText.extract(new String(article.html(), StandardCharsets.UTF_8));
                    if (text.isBlank()) {
                        counts.failed.incrementAndGet();
                        continue;
                    }
                    builder.add(article.path(), article.title(), text);
                    counts.indexed.incrementAndGet();
                } catch (IOException | RuntimeException e) {
                    // One article that will not index must not lose the other three hundred
                    // thousand, and must not take a worker down with it.
                    LOG.log(Level.FINE, "Skipped " + article.path() + " while indexing", e);
                    counts.failed.incrementAndGet();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads one article out of the archive, or null when it is not one. Caller's thread only. */
    private static Article read(ZimArchive archive, long i) {
        try {
            Dirent dirent = archive.direntAt(i);
            if (archive.contentLength(dirent) > MAX_ARTICLE_BYTES) {
                return null;
            }
            return new Article(dirent.fullPath(), dirent.title(), archive.content(dirent));
        } catch (IOException | RuntimeException e) {
            // A corrupt cluster or an unexpected encoding: skip it and carry on.
            LOG.log(Level.FINE, "Skipped entry " + i + " while reading", e);
            return null;
        }
    }

    /**
     * The articles to index, ordered so each cluster is decompressed once.
     *
     * <p>This is the whole performance story. Entries are laid out in the archive in path order,
     * and a ZIM packs about seventy articles into each compressed cluster — but consecutive paths
     * land in <em>different</em> clusters, so walking entries in their natural order jumped
     * between clusters 84,353 times to read 84,877 articles out of 1,254 of them. No cache of any
     * sane size survives that: the OpenStreetMap wiki spent 28 of its 39 seconds decompressing the
     * same clusters over and over.
     *
     * <p>Sorting by cluster first turns that back into 1,254 decompressions. The cost is one extra
     * pass over the dirents, which is under a second, and eight bytes an article.
     *
     * <p>Each element packs the cluster number above the entry index, so a plain sort orders by
     * cluster and then by position within it — which is also the order the blobs sit in.
     */
    private static long[] articleOrder(ZimArchive archive) throws IOException {
        long total = archive.entryCount();
        long[] packed = new long[(int) Math.min(total, Integer.MAX_VALUE)];
        int n = 0;
        for (long i = 0; i < total; i++) {
            try {
                Dirent dirent = archive.direntAt(i);
                if (dirent.isRedirect() || !dirent.hasContent() || dirent.namespace() != archive.contentNamespace()) {
                    continue;
                }
                String mime = archive.mimeType(dirent);
                if (mime == null || !mime.startsWith("text/html")) {
                    continue;
                }
                packed[n++] = (dirent.clusterNumber() << 32) | (i & 0xFFFFFFFFL);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Skipped entry " + i + " while surveying", e);
            }
        }
        long[] order = java.util.Arrays.copyOf(packed, n);
        java.util.Arrays.sort(order);
        return order;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a leftover file is tidied by the next build
                }
            });
        }
    }
}
