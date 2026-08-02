package com.insula.server;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import com.insula.zim.ZimArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The LAN server end to end against a real fixture archive (bound to loopback for the test). */
class LanServerTest {

    private ZimArchive archive;
    private LanServer server;
    private HttpClient client;

    @BeforeEach
    void start() throws Exception {
        archive = ZimArchive.open(Path.of("src/test/resources/zim/nons-wikibooks.zim"));
        server = new LanServer(InetAddress.getLoopbackAddress(), 0);
        server.share("wikibooks", "Wikibooks", 1234, archive);
        client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        archive.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void indexListsSharedArchives() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Wikibooks"));
        assertTrue(response.body().contains("/zim/wikibooks/"));
        assertTrue(response.body().contains("1.2 KB"));
    }

    @Test
    void archiveRootRedirectsToItsMainPageWhichServes() throws Exception {
        HttpResponse<String> redirect = get("/zim/wikibooks/");
        assertEquals(302, redirect.statusCode());
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("/zim/wikibooks/"), location);

        HttpResponse<String> page = get(location);
        assertEquals(200, page.statusCode());
        assertFalse(page.body().isBlank());
    }

    @Test
    void unknownSlugAndUnknownPathAre404(@SuppressWarnings("unused") org.junit.jupiter.api.TestInfo info)
            throws Exception {
        assertEquals(404, get("/zim/nope/").statusCode());
        assertEquals(404, get("/zim/wikibooks/C/no-such-article").statusCode());
        assertEquals(404, get("/favicon.ico").statusCode());
    }

    @Test
    void slugsAreUrlSafe() {
        assertEquals("wikipedia_en_all_mini_2026-06", LanServer.slugFor("wikipedia_en_all_mini_2026-06.zim"));
        assertEquals("weird--name", LanServer.slugFor("Weird !Name.zim"));
    }

    @Test
    void indexEscapesHtmlInTitles() {
        server.share("evil", "<script>alert(1)</script>", 1, archive);
        String html = server.indexPage();
        assertFalse(html.contains("<script>alert"), "titles are data, not markup");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    void phonesCanSeekBecauseRangesAreServed() throws Exception {
        // A phone scrubbing a video is the reason this matters on the LAN side.
        HttpResponse<String> full = get("/zim/wikibooks/C/s/bullet-icon.png");
        assertEquals("bytes", full.headers().firstValue("accept-ranges").orElse(""));

        HttpRequest ranged = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/zim/wikibooks/C/s/bullet-icon.png"))
                .header("Range", "bytes=0-9")
                .GET()
                .build();
        HttpResponse<byte[]> slice = client.send(ranged, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, slice.statusCode());
        assertEquals(10, slice.body().length);
        assertTrue(slice.headers().firstValue("content-range").orElse("").startsWith("bytes 0-9/"));
    }
}
