package com.insula.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import com.insula.catalog.ZimEntry;

/**
 * A tiny properties file written beside each download while it is unfinished business.
 *
 * <p>Verification and repair both need facts that otherwise live only in memory: the Metalink URL
 * (which carries the mirror list, piece hashes, and published SHA-256) and a display title. If the
 * app quits mid-verification, or a file is quarantined, the catalog entry that knew those things
 * may be gone by the next launch — the catalog rotates monthly. The sidecar survives instead.
 *
 * <p>Lifecycle: written when a download pipeline starts, deleted the moment the archive is
 * admitted to the library, deliberately kept alongside a quarantined file so Repair stays
 * possible offline-first (only the metalink fetch itself needs the network).
 */
public final class RecoverySidecar {

    public static final String SUFFIX = ".insula";

    private RecoverySidecar() {}

    /** What repair/resume need to know about an unfinished file. */
    public record Info(String title, String metalinkUrl) {}

    public static Path sidecarFor(Path zim) {
        return zim.resolveSibling(zim.getFileName() + SUFFIX);
    }

    /**
     * Maps a quarantined file back to the ZIM name its sidecar is keyed under:
     * {@code x.zim.corrupt} and {@code x.zim.corrupt.2} both belong to {@code x.zim}.
     */
    public static Path zimFor(Path quarantined) {
        String name = quarantined.getFileName().toString();
        int corrupt = name.indexOf(Quarantine.SUFFIX);
        return corrupt < 0 ? quarantined : quarantined.resolveSibling(name.substring(0, corrupt));
    }

    public static void write(Path zim, ZimEntry entry) throws IOException {
        Properties props = new Properties();
        props.setProperty("title", entry.title());
        props.setProperty("metalinkUrl", entry.metalinkUrl());
        try (OutputStream out = Files.newOutputStream(sidecarFor(zim))) {
            props.store(out, "Insula download recovery info");
        }
    }

    public static Optional<Info> read(Path zim) {
        Path sidecar = sidecarFor(zim);
        if (!Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(sidecar)) {
            props.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        String url = props.getProperty("metalinkUrl", "");
        if (url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Info(props.getProperty("title", zim.getFileName().toString()), url));
    }

    public static void delete(Path zim) {
        try {
            Files.deleteIfExists(sidecarFor(zim));
        } catch (IOException ignored) {
            // a stale sidecar is harmless; it will be ignored once the library row exists
        }
    }
}
