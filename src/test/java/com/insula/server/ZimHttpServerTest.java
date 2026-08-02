package com.insula.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZimHttpServerTest {

    static ZimHttpServer server;
    static ZimArchive archive;
    static String token;
    static final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll
    static void start() throws IOException {
        server = new ZimHttpServer();
        archive = ZimArchive.open(Path.of("src/test/resources/zim/withns-wikibooks.zim"));
        token = server.register(archive);
    }

    @AfterAll
    static void stop() throws IOException {
        server.close();
        archive.close();
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesArticleWithMimeAndCharset() throws Exception {
        Dirent main = archive.mainPage().orElseThrow();
        HttpResponse<String> response = get(server.urlFor(token, main.fullPath()));
        assertEquals(200, response.statusCode());
        assertEquals(
                "text/html; charset=UTF-8",
                response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.body().contains("<html"));
    }

    @Test
    void encodesNonAsciiPaths() throws Exception {
        // Belarusian article path: must survive percent-encoding on the way out and decoding on the way in.
        HttpResponse<String> response = get(server.urlFor(token, "A/Першая_старонка.html"));
        assertEquals(200, response.statusCode());
    }

    @Test
    void redirectEntryAnswers302ToCanonicalPath() throws Exception {
        Dirent redirect = null;
        for (long i = 0; i < archive.entryCount(); i++) {
            Dirent d = archive.direntAt(i);
            if (d.isRedirect()) {
                redirect = d;
                break;
            }
        }
        assertTrue(redirect != null, "fixture should contain a redirect");
        HttpResponse<String> response = get(server.urlFor(token, redirect.fullPath()));
        assertEquals(302, response.statusCode());
        String location = response.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("/zim/" + token + "/"), location);
    }

    @Test
    void unknownPathIs404() throws Exception {
        assertEquals(404, get(server.urlFor(token, "A/definitely_missing.html")).statusCode());
        assertEquals(404, get(server.baseUrl() + "/nope").statusCode());
        assertEquals(404, get(server.baseUrl() + "/zim/zzz/A/x").statusCode());
    }

    @Test
    void aNonWebpImageIsServedByteForByteUntouched() throws Exception {
        // Only WebP is converted. A PNG that came back re-encoded would mean the transcoder is
        // running on everything, quietly inflating every asset the archive already had right.
        try (ZimArchive archive = ZimArchive.open(Path.of("src/test/resources/zim/nons-wikibooks.zim"));
                ZimHttpServer server = new ZimHttpServer()) {
            String token = server.register(archive);
            byte[] expected = archive.content(
                    archive.resolve(archive.entryByUrl("C/s/bullet-icon.png").orElseThrow()));

            HttpURLConnection connection = (HttpURLConnection) URI.create(server.urlFor(token, "C/s/bullet-icon.png"))
                    .toURL()
                    .openConnection();
            assertEquals(200, connection.getResponseCode());
            assertEquals("image/png", connection.getContentType());
            try (InputStream in = connection.getInputStream()) {
                assertArrayEquals(expected, in.readAllBytes());
            }
        }
    }
}
