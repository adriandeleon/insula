package com.offlinewiki.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses a real (trimmed) OPDS v2 feed captured from opds.library.kiwix.org. */
class OpdsCatalogParserTest {

    private static OpdsCatalogParser.Feed parseFixture() throws IOException {
        try (InputStream in = OpdsCatalogParserTest.class.getResourceAsStream("/opds/entries-sample.xml")) {
            return OpdsCatalogParser.parse(in);
        }
    }

    private static OpdsCatalogParser.Feed parse(String xml) throws IOException {
        return OpdsCatalogParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsEveryEntryInTheFeed() throws IOException {
        assertEquals(5, parseFixture().entries().size());
    }

    @Test
    void readsTotalResultsForPaging() throws IOException {
        assertTrue(parseFixture().totalResults() > 0);
    }

    @Test
    void readsTitleFlavourLanguageAndCounts() throws IOException {
        ZimEntry klexikon = byTitle("Klexikon – das Kinderlexikon");
        assertEquals("nopic", klexikon.flavour());
        assertEquals(List.of("deu"), klexikon.languages());
        assertEquals(5659, klexikon.articleCount());
        assertEquals("Klexikon – das Kinderlexikon (nopic)", klexikon.displayName());
    }

    @Test
    void handlesNonAsciiTitles() throws IOException {
        assertEquals("maxi", byTitle("Уикипедия").flavour());
    }

    @Test
    void deduplicatesTheRepeatedLanguageList() throws IOException {
        // The TED entry lists 22 codes with "zho" and "por" repeated.
        List<String> languages = byTitle("TED sex").languages();
        assertEquals(languages.stream().distinct().count(), languages.size());
        assertTrue(languages.contains("eng"));
    }

    @Test
    void sizeComesFromTheAcquisitionLinkLength() throws IOException {
        assertEquals(5_121_103_872L, byTitle("Уикипедия").sizeBytes(), "a >5 GB entry crosses the torrent threshold");
    }

    @Test
    void derivesSidecarUrlsFromTheMetalinkLink() throws IOException {
        ZimEntry entry = byTitle("UnrealIRCd Docs");
        assertTrue(entry.metalinkUrl().endsWith(".meta4"), entry.metalinkUrl());

        String zim = entry.zimUrl();
        assertTrue(zim.endsWith(".zim"), zim);
        assertEquals(zim + ".sha256", entry.sha256Url());
        assertEquals(zim + ".torrent", entry.torrentUrl());
        assertEquals(zim + ".magnet", entry.magnetUrl());
        assertEquals(zim.substring(zim.lastIndexOf('/') + 1), entry.fileName());
    }

    @Test
    void skipsEntriesWithNoUsableAcquisitionLinkInsteadOfFailing() throws IOException {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>No link at all</title></entry>
                  <entry><title>Blank href</title>
                    <link rel="http://opds-spec.org/acquisition/open-access" href=""/></entry>
                  <entry><title>Good</title>
                    <link rel="http://opds-spec.org/acquisition/open-access"
                          href="https://example.org/zim/a.zim.meta4" length="42"/></entry>
                </feed>
                """;
        OpdsCatalogParser.Feed feed = parse(xml);
        assertEquals(1, feed.entries().size(), "one bad entry must not sink the feed");
        assertEquals("Good", feed.entries().getFirst().title());
        assertEquals(-1, feed.totalResults(), "absent totalResults reports -1");
    }

    @Test
    void malformedNumbersFallBackInsteadOfThrowing() throws IOException {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>T</title><articleCount>not-a-number</articleCount>
                    <link rel="http://opds-spec.org/acquisition/open-access"
                          href="https://example.org/a.zim.meta4" length="huge"/></entry>
                </feed>
                """;
        ZimEntry entry = parse(xml).entries().getFirst();
        assertEquals(0, entry.articleCount());
        assertEquals(0, entry.sizeBytes());
    }

    @Test
    void entryFieldsAreNotShadowedByNestedElements() throws IOException {
        // <author><name>…</name></author> must not be read as the entry's own <name>.
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>T</title>
                    <author><name>Stack Exchange</name></author>
                    <name>real_archive_name</name>
                    <link rel="http://opds-spec.org/acquisition/open-access"
                          href="https://example.org/a.zim.meta4" length="1"/>
                  </entry>
                </feed>
                """;
        assertEquals("real_archive_name", parse(xml).entries().getFirst().name());
    }

    @Test
    void rejectsXxeDoctype() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>&xxe;</title></entry></feed>
                """;
        assertThrows(IOException.class, () -> parse(xml));
    }

    private static ZimEntry byTitle(String title) throws IOException {
        return parseFixture().entries().stream()
                .filter(e -> e.title().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry titled " + title));
    }
}
