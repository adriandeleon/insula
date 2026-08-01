package com.offlinewiki.download;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A loopback HTTP server that serves fixed bytes with {@code Range} support, so the transport can
 * be exercised end-to-end without touching the network. Behaviour can be perturbed per instance to
 * reproduce the failure modes that matter: a mirror that ignores ranges, returns errors, or serves
 * corrupt bytes.
 */
final class FakeMirror implements AutoCloseable {

    /** How a mirror misbehaves. */
    enum Behaviour {
        NORMAL,
        /** Answers 200 with the whole file, ignoring the Range header. */
        IGNORES_RANGE,
        /** Always 500. */
        ERRORS,
        /** Serves bytes that do not match the piece hashes. */
        CORRUPTS
    }

    private final HttpServer server;
    private final byte[] content;
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile Behaviour behaviour;

    FakeMirror(byte[] content, Behaviour behaviour) throws IOException {
        this.content = content;
        this.behaviour = behaviour;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-mirror");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/file.zim";
    }

    int requestCount() {
        return requestCount.get();
    }

    void setBehaviour(Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        try (exchange) {
            if (behaviour == Behaviour.ERRORS) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            byte[] body;
            int status;
            if (range == null || behaviour == Behaviour.IGNORES_RANGE) {
                body = content;
                status = 200;
            } else {
                String[] bounds = range.replace("bytes=", "").split("-");
                int from = Integer.parseInt(bounds[0]);
                int to = Math.min(Integer.parseInt(bounds[1]), content.length - 1);
                body = Arrays.copyOfRange(content, from, to + 1);
                status = 206;
                exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + content.length);
            }
            if (behaviour == Behaviour.CORRUPTS) {
                body = body.clone();
                if (body.length > 0) {
                    body[0] ^= 0xFF;
                }
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }

    /** Serves a Metalink pointing at the given mirrors, plus the file itself. */
    static FakeMirror withMetalink(byte[] content, Function<String, String> metalinkBody) throws IOException {
        FakeMirror mirror = new FakeMirror(content, Behaviour.NORMAL);
        mirror.server.createContext("/file.zim.meta4", exchange -> {
            byte[] body = metalinkBody.apply(mirror.url()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/metalink4+xml");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        return mirror;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
