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

        long total = archive.entryCount();
        long[] order = articleOrder(archive);
        long indexed = 0;
        long skipped = total - order.length;
        boolean stopped = false;

        try (FullTextIndex.Builder builder = FullTextIndex.builder(staging)) {
            for (int at = 0; at < order.length; at++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    stopped = true;
                    break;
                }
                if (progress != null && (at % 512 == 0 || at == order.length - 1)) {
                    progress.at(at + 1, order.length, indexed);
                }
                if (indexOne(archive, (int) (order[at] & 0xFFFFFFFFL), builder)) {
                    indexed++;
                } else {
                    skipped++;
                }
            }
        }

        if (stopped) {
            deleteTree(staging);
            return new Result(indexed, skipped, true);
        }
        deleteTree(indexDir);
        Files.createDirectories(indexDir.getParent());
        Files.move(staging, indexDir);
        return new Result(indexed, skipped, false);
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

    /** @return whether this entry was an article and went into the index */
    private static boolean indexOne(ZimArchive archive, long i, FullTextIndex.Builder builder) {
        try {
            Dirent dirent = archive.direntAt(i);
            // Redirects hold no text of their own, and only the content namespace holds articles:
            // metadata, layout templates and the archive's own search index are not reading.
            if (dirent.isRedirect() || !dirent.hasContent() || dirent.namespace() != archive.contentNamespace()) {
                return false;
            }
            String mime = archive.mimeType(dirent);
            if (mime == null || !mime.startsWith("text/html")) {
                return false;
            }
            if (archive.contentLength(dirent) > MAX_ARTICLE_BYTES) {
                return false;
            }
            String text = HtmlText.extract(new String(archive.content(dirent), StandardCharsets.UTF_8));
            if (text.isBlank()) {
                return false;
            }
            builder.add(dirent.fullPath(), dirent.title(), text);
            return true;
        } catch (IOException | RuntimeException e) {
            // One unreadable entry — a corrupt cluster, an unexpected encoding — must not lose the
            // other three hundred thousand. It is skipped and the pass carries on.
            LOG.log(Level.FINE, "Skipped entry " + i + " while indexing", e);
            return false;
        }
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
