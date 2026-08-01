package com.insula.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.image.Image;

import com.insula.AppInfo;

/**
 * Catalog card icons: fetched lazily, cached on disk beside the catalog, and bounded in memory.
 *
 * <p>The in-memory bound matters more than it looks: each {@link Image} pins a GPU texture, and a
 * scroll through 2,600 cards must not accumulate 2,600 textures. Disk-cached icons survive
 * restarts, so the store works offline with icons after the first browse.
 */
final class IconCache {

    /** 48px PNGs are ~2-6 KB; 256 of them is trivial in RAM and covers several screenfuls. */
    private static final int MEMORY_ENTRIES = 256;

    private final Path directory;
    private final HttpClient http;

    private final Map<String, Image> memory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "icon-cache");
        t.setDaemon(true);
        return t;
    });

    IconCache(Path directory) {
        this.directory = directory;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Delivers the icon on the FX thread, or never calls back (the card keeps its monogram).
     * Synchronous when already in memory, so scrolling back up never flickers.
     */
    void icon(String url, Consumer<Image> onLoaded) {
        if (url == null || url.isBlank()) {
            return;
        }
        Image cached;
        synchronized (memory) {
            cached = memory.get(url);
        }
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        executor.execute(() -> {
            byte[] bytes = diskOrFetch(url);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            Image image = new Image(new java.io.ByteArrayInputStream(bytes), 48, 48, true, true);
            if (image.isError()) {
                return;
            }
            synchronized (memory) {
                memory.put(url, image);
            }
            Platform.runLater(() -> onLoaded.accept(image));
        });
    }

    private byte[] diskOrFetch(String url) {
        Path file = directory.resolve(hash(url) + ".png");
        try {
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", AppInfo.USER_AGENT)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = response.body()) {
                bytes = in.readNBytes(512 * 1024); // an icon; anything bigger is wrong
            }
            Files.createDirectories(directory);
            Files.write(file, bytes);
            return bytes;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String hash(String url) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void close() {
        executor.shutdownNow();
    }
}
