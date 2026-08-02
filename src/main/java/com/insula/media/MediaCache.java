package com.insula.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds transcoded videos on disk so a rewatch is instant instead of another 20-second encode.
 *
 * <p>Bounded by <b>total bytes</b>, evicting least-recently-used, for the same reason the cluster
 * cache is: one transcoded talk is ~38 MB, so a count-based bound would bound the wrong dimension
 * — ten entries could be anywhere from 40 MB to several GB. Eviction reads last-access time where
 * the filesystem records it and falls back to modification time, which is what a freshly written
 * encode has.
 */
public final class MediaCache {

    private static final Logger LOG = Logger.getLogger(MediaCache.class.getName());

    /** Default ceiling. Big enough to keep a session's worth of talks, small enough to notice. */
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path directory;
    private final long maxBytes;

    public MediaCache(Path directory) {
        this(directory, DEFAULT_MAX_BYTES);
    }

    public MediaCache(Path directory, long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    public Path directory() {
        return directory;
    }

    /** The path a given cache name occupies, whether or not it exists yet. */
    public Path fileFor(String name) {
        return directory.resolve(name);
    }

    /**
     * A finished encode for this name, or null. A zero-length file counts as absent. A hit is
     * touched so eviction can see it as recently used.
     */
    public Path lookup(String name) throws IOException {
        Path file = fileFor(name);
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return null;
        }
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not touch a cache hit", e); // eviction order only
        }
        return file;
    }

    public void prepare() throws IOException {
        Files.createDirectories(directory);
    }

    /** Evicts oldest-first until the directory fits the budget. Called after each new encode. */
    public void evictToBudget() {
        try {
            if (!Files.isDirectory(directory)) {
                return;
            }
            List<Path> files;
            try (var stream = Files.list(directory)) {
                files = stream.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList());
            }
            long total = 0;
            for (Path file : files) {
                total += Files.size(file);
            }
            if (total <= maxBytes) {
                return;
            }
            List<Path> oldestFirst = new ArrayList<>(files);
            oldestFirst.sort(Comparator.comparingLong(MediaCache::lastUsed));
            for (Path file : oldestFirst) {
                if (total <= maxBytes) {
                    break;
                }
                long size = Files.size(file);
                if (Files.deleteIfExists(file)) {
                    total -= size;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not evict from the media cache", e);
        }
    }

    private static long lastUsed(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
