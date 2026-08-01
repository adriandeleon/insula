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
 * Loopback-only HTTP server that serves ZIM entries at their real archive paths:
 * {@code http://127.0.0.1:<port>/zim/<token>/<ns>/<path>}. Serving at real paths means the
 * relative links, images and stylesheets inside the archived HTML resolve with no rewriting.
 * ZIM redirect entries answer with HTTP 302 to the target's canonical path so the browser's
 * base URL stays correct for relative resolution.
 */
public final class ZimHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, ZimArchive> archives = new ConcurrentHashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger();

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
            byte[] body = archive.content(d);
            String mime = archive.mimeType(d);
            if (mime.startsWith("text/")) {
                mime = mime + "; charset=UTF-8";
            }
            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            sendText(exchange, 500, "Error: " + e.getMessage());
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
