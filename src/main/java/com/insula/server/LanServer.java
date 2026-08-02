package com.insula.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;

import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves the library to other devices on the local network — a phone pointed at the QR code gets
 * a browsable index of every shared archive, kiwix-serve style but one toggle away.
 *
 * <p>Unlike {@link ZimHttpServer} (loopback, ephemeral port, random tokens — an implementation
 * detail of the reader) this binds the given address for real LAN clients, uses stable
 * human-readable slugs in URLs, and serves an index page. Read-only by construction: every
 * response is bytes out of a ZIM archive or generated HTML; there is no write surface.
 *
 * <p>Sharing is deliberately session-only — it never survives a restart, because silently serving
 * files to the network is how an app loses trust.
 */
public final class LanServer implements AutoCloseable {

    /** One shared archive: what the index shows and where its content mounts. */
    public record Shared(String slug, String title, long sizeBytes, ZimArchive archive) {}

    private final HttpServer server;
    private final Map<String, Shared> shared = new ConcurrentSkipListMap<>();

    public LanServer(InetAddress bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "lan-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Mounts an archive at {@code /zim/<slug>/}. The caller keeps ownership of the archive. */
    public void share(String slug, String title, long sizeBytes, ZimArchive archive) {
        shared.put(slug, new Shared(slug, title, sizeBytes, archive));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public int sharedCount() {
        return shared.size();
    }

    /** A URL-safe slug from a ZIM file name: {@code wikipedia_en_all_mini_2026-06.zim} → base name. */
    public static String slugFor(String fileName) {
        String base = fileName.endsWith(".zim") ? fileName.substring(0, fileName.length() - 4) : fileName;
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    /** The machine's site-local IPv4 address, for building the URL other devices can reach. */
    public static Optional<String> lanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.isSiteLocalAddress() && address.getAddress().length == 4) {
                        return Optional.of(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                sendHtml(exchange, 200, indexPage());
                return;
            }
            if (!path.startsWith("/zim/")) {
                sendHtml(exchange, 404, errorPage("Not found"));
                return;
            }
            String rest = path.substring(5);
            int slash = rest.indexOf('/');
            String slug = slash < 0 ? rest : rest.substring(0, slash);
            Shared entry = shared.get(slug);
            if (entry == null) {
                sendHtml(exchange, 404, errorPage("No such archive"));
                return;
            }
            String zimPath = slash < 0 ? "" : rest.substring(slash + 1);
            if (zimPath.isEmpty()) {
                // The book's front door: redirect to its main page's canonical path.
                Optional<Dirent> main = entry.archive().mainPage();
                if (main.isEmpty()) {
                    sendHtml(exchange, 404, errorPage("This archive has no main page"));
                    return;
                }
                redirect(exchange, "/zim/" + slug + "/" + main.get().fullPath());
                return;
            }
            serveEntry(exchange, entry.archive(), slug, zimPath);
        } catch (URISyntaxException | RuntimeException e) {
            sendHtml(exchange, 500, errorPage("Error: " + e.getMessage()));
        }
    }

    private void serveEntry(HttpExchange exchange, ZimArchive archive, String slug, String zimPath)
            throws IOException, URISyntaxException {
        Optional<Dirent> entry = archive.entryByUrl(zimPath);
        if (entry.isEmpty()) {
            sendHtml(exchange, 404, errorPage("Not in this archive: " + zimPath));
            return;
        }
        Dirent d = entry.get();
        if (d.isRedirect()) {
            redirect(exchange, "/zim/" + slug + "/" + archive.resolve(d).fullPath());
            return;
        }
        String mime = archive.mimeType(d);
        if (mime.startsWith("text/")) {
            mime = mime + "; charset=UTF-8";
        }
        long total = archive.contentLength(d);
        exchange.getResponseHeaders().set("Content-Type", mime);
        // Phones seek video; without this a scrub bar re-fetches from the start.
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");

        ByteRanges.Request request =
                ByteRanges.parse(exchange.getRequestHeaders().getFirst("Range"), total);
        if (request instanceof ByteRanges.Request.Unsatisfiable) {
            exchange.getResponseHeaders().set("Content-Range", ByteRanges.unsatisfiedRange(total));
            exchange.sendResponseHeaders(416, -1);
            return;
        }
        byte[] body;
        if (request instanceof ByteRanges.Request.Partial partial) {
            body = archive.contentRange(d, partial.start(), Math.toIntExact(partial.length()));
            exchange.getResponseHeaders().set("Content-Range", ByteRanges.contentRange(partial, total));
            exchange.sendResponseHeaders(206, body.length == 0 ? -1 : body.length);
        } else {
            body = archive.content(d);
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        }
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** A phone-first index of the shared archives. Static HTML; no scripts, no external assets. */
    String indexPage() {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Insula — shared library</title>
                <style>
                  body { font-family: system-ui, sans-serif; margin: 0; background: #f6f8fa; color: #1f2328; }
                  header { background: #1c2027; color: #fff; padding: 16px 20px; }
                  header h1 { margin: 0; font-size: 1.2rem; }
                  main { max-width: 640px; margin: 0 auto; padding: 16px; }
                  a.book { display: block; background: #fff; border: 1px solid #d0d7de; border-radius: 10px;
                           padding: 14px 16px; margin: 10px 0; text-decoration: none; color: inherit; }
                  a.book b { font-size: 1.05rem; }
                  a.book span { display: block; color: #656d76; font-size: 0.85rem; margin-top: 2px; }
                  p.empty { color: #656d76; }
                </style></head><body>
                <header><h1>Insula — shared library</h1></header><main>
                """);
        if (shared.isEmpty()) {
            html.append("<p class=\"empty\">Nothing is shared right now.</p>");
        }
        for (Shared entry : shared.values()) {
            html.append("<a class=\"book\" href=\"/zim/")
                    .append(entry.slug())
                    .append("/\"><b>")
                    .append(escape(entry.title()))
                    .append("</b><span>")
                    .append(humanBytes(entry.sizeBytes()))
                    .append("</span></a>\n");
        }
        return html.append("</main></body></html>").toString();
    }

    private static String errorPage(String message) {
        return "<!doctype html><html><body><h2>" + escape(message) + "</h2></body></html>";
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException, URISyntaxException {
        exchange.getResponseHeaders().set("Location", new URI(null, null, location, null).toASCIIString());
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        for (String unit : new String[] {"KB", "MB", "GB", "TB"}) {
            value /= 1024;
            if (value < 1024) {
                return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.0f PB", value / 1024);
    }

    @Override
    public void close() {
        server.stop(0);
        shared.clear();
    }
}
