package com.insula.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Loopback-only HTTP server that serves ZIM entries at their real archive paths, transcoding
 * WebP images on the way out because WebView cannot display them (see {@link WebpTranscoder}):
 * {@code http://127.0.0.1:<port>/zim/<token>/<ns>/<path>}. Serving at real paths means the
 * relative links, images and stylesheets inside the archived HTML resolve with no rewriting.
 * ZIM redirect entries answer with HTTP 302 to the target's canonical path so the browser's
 * base URL stays correct for relative resolution.
 */
public final class ZimHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, ZimArchive> archives = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final WebpTranscoder transcoder = new WebpTranscoder();
    private final Map<String, MediaStream> streams = new ConcurrentHashMap<>();

    public ZimHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "zim-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Registers an archive and returns its URL token. The caller keeps ownership (and closes it). */
    public String register(ZimArchive archive) {
        String token = "z" + tokenCounter.incrementAndGet();
        archives.put(token, archive);
        return token;
    }

    public void unregister(String token) {
        archives.remove(token);
    }

    /** A playable stream: a complete HLS playlist over segments produced on demand. */
    public interface MediaStream {

        /** The playlist text; {@code segmentUriFormat} takes the segment index. */
        String playlist(String segmentUriFormat);

        /** One segment's bytes, encoding it if needed. Null when the index is out of range. */
        byte[] segment(int index) throws IOException;
    }

    /**
     * Publishes a stream at {@code /hls/<token>/index.m3u8}. The article page is served over http,
     * so serving the video from here keeps it same-origin — and the player asks for segments by
     * URL, which is what lets them be produced only when actually needed.
     */
    public String registerStream(MediaStream stream) {
        String token = "s" + tokenCounter.incrementAndGet();
        streams.put(token, stream);
        return token;
    }

    public void unregisterStream(String token) {
        streams.remove(token);
    }

    public String streamUrl(String token) {
        return baseUrl() + "/hls/" + token + "/index.m3u8";
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Absolute URL for a full ZIM path like {@code "C/Foo Bar"} (percent-encoded as needed). */
    public String urlFor(String token, String fullPath) {
        try {
            URI uri = new URI(null, null, "/zim/" + token + "/" + fullPath, null);
            return baseUrl() + uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Bad ZIM path: " + fullPath, e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath(); // already percent-decoded
            if (path.startsWith("/hls/")) {
                sendStream(exchange, path);
                return;
            }
            if (!path.startsWith("/zim/")) {
                sendText(exchange, 404, "Not found");
                return;
            }
            int slash = path.indexOf('/', 5);
            if (slash < 0) {
                sendText(exchange, 404, "Not found");
                return;
            }
            ZimArchive archive = archives.get(path.substring(5, slash));
            String zimPath = path.substring(slash + 1);
            if (archive == null) {
                sendText(exchange, 404, "Unknown archive");
                return;
            }
            Optional<Dirent> entry = archive.entryByUrl(zimPath);
            if (entry.isEmpty()) {
                sendText(exchange, 404, "Not in this archive: " + zimPath);
                return;
            }
            Dirent d = entry.get();
            if (d.isRedirect()) {
                Dirent target = archive.resolve(d);
                String location = path.substring(0, slash + 1) + target.fullPath();
                exchange.getResponseHeaders().set("Location", new URI(null, null, location, null).toASCIIString());
                exchange.sendResponseHeaders(302, -1);
                return;
            }
            String mime = archive.mimeType(d);
            if (WebpTranscoder.isWebp(mime)) {
                // WebView cannot decode WebP, and every modern ZIM stores its images that way.
                // Transcoding needs the whole image, so this branch serves from memory; images
                // are small and no client range-requests them.
                WebpTranscoder.Transcoded converted = transcoder.transcode(path, archive.content(d), mime);
                sendBytes(exchange, converted.bytes(), converted.mimeType());
                return;
            }
            if (mime.startsWith("text/")) {
                mime = mime + "; charset=UTF-8";
            }
            sendEntry(exchange, archive, d, mime);
        } catch (URISyntaxException | RuntimeException e) {
            sendText(exchange, 500, "Error: " + e.getMessage());
        }
    }

    /**
     * Serves an entry, honouring a {@code Range} request by reading only the window asked for.
     *
     * <p>Range support is what makes an external player usable: without it, seeking to the middle
     * of a talk means re-reading from byte zero. Reading the slice out of the archive rather than
     * the whole blob keeps a seek proportional to the request instead of to the file.
     */
    private static void sendEntry(HttpExchange exchange, ZimArchive archive, Dirent d, String mime) throws IOException {
        long total = archive.contentLength(d);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

        ByteRanges.Request request =
                ByteRanges.parse(exchange.getRequestHeaders().getFirst("Range"), total);
        if (request instanceof ByteRanges.Request.Unsatisfiable) {
            exchange.getResponseHeaders().set("Content-Range", ByteRanges.unsatisfiedRange(total));
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        if (request instanceof ByteRanges.Request.Partial partial) {
            byte[] slice = archive.contentRange(d, partial.start(), Math.toIntExact(partial.length()));
            exchange.getResponseHeaders().set("Content-Range", ByteRanges.contentRange(partial, total));
            exchange.sendResponseHeaders(206, slice.length == 0 ? -1 : slice.length);
            writeBody(exchange, slice);
            return;
        }
        exchange.sendResponseHeaders(200, total == 0 ? -1 : total);
        writeBody(exchange, archive.content(d));
    }

    /**
     * Serves {@code /hls/<token>/index.m3u8} and {@code /hls/<token>/<n>.ts}.
     *
     * <p>A segment request blocks while ffmpeg produces it (~165 ms measured), which is why the
     * server runs a pool rather than a single thread — one video buffering must not stall the
     * article that contains it.
     */
    private void sendStream(HttpExchange exchange, String path) throws IOException {
        String rest = path.substring(5);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            sendText(exchange, 404, "Not found");
            return;
        }
        MediaStream stream = streams.get(rest.substring(0, slash));
        String resource = rest.substring(slash + 1);
        if (stream == null) {
            sendText(exchange, 404, "No such stream");
            return;
        }
        if (resource.equals("index.m3u8")) {
            byte[] body = stream.playlist("%d.ts").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            exchange.sendResponseHeaders(200, body.length);
            writeBody(exchange, body);
            return;
        }
        if (!resource.endsWith(".ts")) {
            sendText(exchange, 404, "Not found");
            return;
        }
        try {
            byte[] body = stream.segment(Integer.parseInt(resource.substring(0, resource.length() - 3)));
            if (body == null) {
                sendText(exchange, 404, "No such segment");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "video/mp2t");
            exchange.sendResponseHeaders(200, body.length);
            writeBody(exchange, body);
        } catch (NumberFormatException e) {
            sendText(exchange, 404, "Not found");
        }
    }

    private static void sendBytes(HttpExchange exchange, byte[] body, String mime) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        writeBody(exchange, body);
    }

    private static void writeBody(HttpExchange exchange, byte[] body) throws IOException {
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void sendText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = ("<html><body><h2>" + status + "</h2><p>" + message + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        archives.clear();
    }
}
