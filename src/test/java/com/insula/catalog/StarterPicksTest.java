package com.insula.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterPicksTest {

    private static ZimEntry entry(String name, String flavour, long size) {
        return new ZimEntry(
                "id-" + name + flavour,
                name,
                "",
                name,
                flavour,
                List.of("eng"),
                "wikipedia",
                100,
                0,
                "https://example.org/" + name + (flavour.isEmpty() ? "" : "_" + flavour) + "_2026-06.zim.meta4",
                size);
    }

    @Test
    void picksAreNamesNotUrls() {
        for (StarterPicks.Pick pick : StarterPicks.PICKS) {
            assertTrue(pick.name().matches("[a-z0-9_.-]+"), "a pick must be a catalog name: " + pick.name());
        }
    }

    @Test
    void resolveKeepsPickOrderAndSkipsMissingNames() {
        List<CatalogGroups.TitleGroup> groups = CatalogGroups.group(List.of(
                entry("vikidia_en_all", "", 500),
                entry("wikipedia_en_ray-charles", "maxi", 700_000),
                entry("unrelated_thing", "", 10)));
        List<StarterPicks.Resolved> resolved = StarterPicks.resolve(groups, Long.MAX_VALUE);
        assertEquals(2, resolved.size(), "only names present in the catalog resolve");
        assertEquals(
                "wikipedia_en_ray-charles", resolved.getFirst().pick().name(), "PICKS order wins, not catalog order");
        assertEquals("vikidia_en_all", resolved.getLast().pick().name());
    }

    @Test
    void resolvedVariantRespectsFreeDisk() {
        List<CatalogGroups.TitleGroup> groups = CatalogGroups.group(
                List.of(entry("wikivoyage_en_all", "nopic", 100), entry("wikivoyage_en_all", "maxi", 10_000)));
        StarterPicks.Resolved tight = StarterPicks.resolve(groups, 500).getFirst();
        assertEquals(100, tight.entry().sizeBytes(), "with little space the small flavour is chosen");
        StarterPicks.Resolved roomy = StarterPicks.resolve(groups, 1_000_000).getFirst();
        assertEquals(10_000, roomy.entry().sizeBytes());
    }

    @Test
    void emptyCatalogResolvesToNothing() {
        assertTrue(StarterPicks.resolve(List.of(), Long.MAX_VALUE).isEmpty());
    }
}
