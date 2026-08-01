package com.insula.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grouping over real feed entries captured from the live catalog. */
class CatalogGroupsTest {

    private static List<ZimEntry> fixtureEntries() throws IOException {
        try (InputStream in = CatalogGroupsTest.class.getResourceAsStream("/opds/groups-sample.xml")) {
            return OpdsCatalogParser.parse(in).entries();
        }
    }

    @Test
    void collapsesFlavoursOfOneTitleIntoOneGroup() throws IOException {
        // 7 real entries: wikipedia_en_all ×3, vikidia_es_all ×2, wiktionary_es_all ×2.
        List<CatalogGroups.TitleGroup> groups = CatalogGroups.group(fixtureEntries());
        assertEquals(3, groups.size(), "seven rows, three products");

        CatalogGroups.TitleGroup wikipedia = byName(groups, "wikipedia_en_all");
        assertEquals(3, wikipedia.variants().size());
        assertEquals(
                List.of("mini", "nopic", "maxi"),
                wikipedia.variants().stream()
                        .map(CatalogGroups.Variant::flavourLabel)
                        .toList(),
                "variants ordered smallest first");
    }

    @Test
    void aBlankFlavourDisplaysAsAll() throws IOException {
        CatalogGroups.TitleGroup wiktionary = byName(CatalogGroups.group(fixtureEntries()), "wiktionary_es_all");
        assertTrue(
                wiktionary.variants().stream().anyMatch(v -> v.flavourLabel().equals("all")),
                wiktionary.variants().toString());
    }

    @Test
    void cardFactsComeFromTheRichestVariantAndNewestDate() throws IOException {
        CatalogGroups.TitleGroup wikipedia = byName(CatalogGroups.group(fixtureEntries()), "wikipedia_en_all");
        long maxSize = wikipedia.variants().stream()
                .mapToLong(v -> v.entry().sizeBytes())
                .max()
                .orElseThrow();
        assertEquals(maxSize, wikipedia.variants().getLast().entry().sizeBytes());
        // In the captured feed the maxi build (2026-02) is OLDER than mini/nopic (2026-06):
        // the card's date must be the newest across variants, not the richest variant's.
        assertEquals("2026-06-17", wikipedia.newestUpdated());
        assertTrue(wikipedia.articleCount() > 0);
        assertFalse(wikipedia.illustrationHref().isBlank(), "every live entry carries an illustration link");
    }

    @Test
    void defaultVariantIsTheLargestThatFits() throws IOException {
        CatalogGroups.TitleGroup wikipedia = byName(CatalogGroups.group(fixtureEntries()), "wikipedia_en_all");
        List<CatalogGroups.Variant> v = wikipedia.variants();
        long miniSize = v.get(0).entry().sizeBytes();
        long nopicSize = v.get(1).entry().sizeBytes();
        long maxiSize = v.get(2).entry().sizeBytes();

        assertEquals("maxi", wikipedia.defaultVariant(maxiSize + 1).flavourLabel());
        assertEquals("nopic", wikipedia.defaultVariant(maxiSize - 1).flavourLabel());
        assertEquals("mini", wikipedia.defaultVariant(nopicSize - 1).flavourLabel());
        // Nothing fits: still offer the smallest rather than nothing — the user may free space.
        assertEquals("mini", wikipedia.defaultVariant(miniSize - 1).flavourLabel());
    }

    @Test
    void groupingIsStableAndOrderPreserving() throws IOException {
        List<ZimEntry> entries = fixtureEntries();
        List<CatalogGroups.TitleGroup> once = CatalogGroups.group(entries);
        List<CatalogGroups.TitleGroup> twice = CatalogGroups.group(entries);
        assertEquals(
                once.stream().map(CatalogGroups.TitleGroup::name).toList(),
                twice.stream().map(CatalogGroups.TitleGroup::name).toList());
    }

    @Test
    void sameNameInDifferentLanguagesStaysSeparate() {
        ZimEntry en = entry("wikipedia", "eng", "mini", 10);
        ZimEntry es = entry("wikipedia", "spa", "mini", 10);
        assertEquals(2, CatalogGroups.group(List.of(en, es)).size(), "language is part of the identity");
    }

    private static ZimEntry entry(String name, String lang, String flavour, long size) {
        return new ZimEntry(
                "id-" + name + lang,
                name,
                "",
                name,
                flavour,
                List.of(lang),
                "wikipedia",
                1,
                0,
                "https://example.org/" + name + ".zim.meta4",
                size);
    }

    private static CatalogGroups.TitleGroup byName(List<CatalogGroups.TitleGroup> groups, String name) {
        return groups.stream()
                .filter(g -> g.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no group named " + name));
    }
}
