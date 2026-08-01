package com.insula.download;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses a real MirrorBrain sidecar captured from download.kiwix.org. */
class MetalinkParserTest {

    private static Metalink fixture() throws IOException {
        try (InputStream in = MetalinkParserTest.class.getResourceAsStream("/meta4/wikipedia_en_100_mini.meta4")) {
            return MetalinkParser.parse(in);
        }
    }

    private static Metalink parse(String xml) throws IOException {
        return MetalinkParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsFileNameSizeAndWholeFileHashes() throws IOException {
        Metalink m = fixture();
        assertEquals("wikipedia_en_100_mini_2026-07.zim", m.fileName());
        assertEquals(4_621_915L, m.size());
        assertEquals("b3d5db724e2ef884eaf43e3677ba2dc5c4d17619114b3de4602c119ca23dcfcd", m.sha256());
        assertEquals("0f1d290f4f501994466e700fc9c63dfa", m.md5());
        assertTrue(m.sha256Hash().isPresent());
    }

    @Test
    void readsTheMirrorList() throws IOException {
        Metalink m = fixture();
        assertEquals(9, m.mirrors().size());
        assertTrue(
                m.mirrors().stream().allMatch(u -> u.startsWith("https://")),
                m.mirrors().toString());
    }

    @Test
    void excludesThePublisherUrlWhichIsNotAMirror() throws IOException {
        // The sidecar carries <publisher><url>https://kiwix.org</url></publisher> outside <file>.
        // Counting every <url> in the document (10) would hand the transport a download URL that
        // serves the project home page instead of the archive.
        assertFalse(fixture().mirrors().contains("https://kiwix.org"));
        assertTrue(
                fixture().mirrors().stream().allMatch(u -> u.endsWith(".zim")),
                "every mirror should address the archive itself");
    }

    @Test
    void readsPieceHashesForPerChunkVerification() throws IOException {
        Metalink m = fixture();
        assertTrue(m.hasPieceHashes());
        assertEquals(4 * 1024 * 1024, m.pieceLength());
        assertEquals("sha-1", m.pieceHashType());
        assertEquals(2, m.pieceHashes().size(), "4.6 MB over 4 MiB pieces");
    }

    @Test
    void pieceRangesTileTheFileAndTheLastPieceIsShort() throws IOException {
        Metalink m = fixture();
        assertArrayEquals(new long[] {0, 4_194_304}, m.pieceRange(0));
        assertArrayEquals(new long[] {4_194_304, 4_621_915}, m.pieceRange(1));
        assertThrows(IndexOutOfBoundsException.class, () -> m.pieceRange(2));
    }

    @Test
    void ordersMirrorsByPriorityBestFirst() throws IOException {
        String xml = """
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="a.zim"><size>10</size>
                    <url priority="7">https://slow.example/a.zim</url>
                    <url priority="1">https://fast.example/a.zim</url>
                    <url priority="3">https://mid.example/a.zim</url>
                  </file>
                </metalink>
                """;
        assertEquals(
                java.util.List.of(
                        "https://fast.example/a.zim", "https://mid.example/a.zim", "https://slow.example/a.zim"),
                parse(xml).mirrors());
    }

    @Test
    void dropsMirrorsHttpClientCannotFetch() throws IOException {
        // MirrorBrain also advertises ftp:// and rsync:// — picking one would just fail.
        String xml = """
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="a.zim"><size>10</size>
                    <url priority="1">ftp://mirror.example/a.zim</url>
                    <url priority="2">rsync://mirror.example/a.zim</url>
                    <url priority="3">https://mirror.example/a.zim</url>
                  </file>
                </metalink>
                """;
        assertEquals(
                java.util.List.of("https://mirror.example/a.zim"), parse(xml).mirrors());
    }

    @Test
    void toleratesAFileWithNoPieceHashes() throws IOException {
        String xml = """
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="a.zim"><size>10</size>
                    <hash type="sha-256">abc</hash>
                    <url priority="1">https://mirror.example/a.zim</url>
                  </file>
                </metalink>
                """;
        Metalink m = parse(xml);
        assertFalse(m.hasPieceHashes(), "no pieces → chunk verification unavailable, whole-file still works");
        assertEquals("abc", m.sha256());
    }

    @Test
    void rejectsAMetalinkWithNoFileElement() {
        String xml = "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"></metalink>";
        assertThrows(IOException.class, () -> parse(xml));
    }

    @Test
    void rejectsXxeDoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE metalink [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="&xxe;"/></metalink>
                """;
        assertThrows(IOException.class, () -> parse(xml));
    }
}
