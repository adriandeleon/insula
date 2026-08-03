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
         * @param scanned entries looked at so far
         * @param total entries in the archive
         * @param indexed how many of them turned out to be articles worth indexing
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
        long indexed = 0;
        long skipped = 0;
        boolean stopped = false;

        try (FullTextIndex.Builder builder = FullTextIndex.builder(staging)) {
            for (long i = 0; i < total; i++) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    stopped = true;
                    break;
                }
                if (progress != null && (i % 512 == 0 || i == total - 1)) {
                    progress.at(i + 1, total, indexed);
                }
                if (indexOne(archive, i, builder)) {
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
