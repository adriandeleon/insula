package com.insula.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckTest {

    /** A catalog row whose fileName derives from the metalink URL, like the real parser's. */
    private static ZimEntry entry(String fileBase) {
        return new ZimEntry(
                "id-" + fileBase,
                fileBase,
                "",
                fileBase.replaceFirst("_\\d{4}-\\d{2}$", ""),
                "",
                List.of("eng"),
                "wikipedia",
                100,
                0,
                "https://example.org/" + fileBase + ".zim.meta4",
                1000);
    }

    @Test
    void buildDateParsesAndToleratesUndatedNames() {
        assertEquals("2026-02", UpdateCheck.buildDateOf("wikipedia_en_all_maxi_2026-02.zim"));
        assertEquals("2026-02", UpdateCheck.buildDateOf("wikipedia_en_all_maxi_2026-02"));
        assertEquals("", UpdateCheck.buildDateOf("custom.zim"));
        assertEquals("", UpdateCheck.buildDateOf("notes_20260201.zim"), "eight-digit stamps are not the convention");
    }

    @Test
    void newerCatalogBuildIsAnUpdate() {
        List<UpdateCheck.Update> updates = UpdateCheck.findUpdates(
                List.of("wikipedia_en_all_maxi_2026-02.zim"), List.of(entry("wikipedia_en_all_maxi_2026-06")));
        assertEquals(1, updates.size());
        assertEquals("wikipedia_en_all_maxi_2026-02.zim", updates.getFirst().installedFileName());
        assertEquals(
                "wikipedia_en_all_maxi_2026-06.zim",
                updates.getFirst().replacement().fileName());
    }

    @Test
    void sameOrOlderCatalogBuildIsNot() {
        assertTrue(UpdateCheck.findUpdates(
                        List.of("wikipedia_en_all_maxi_2026-06.zim"), List.of(entry("wikipedia_en_all_maxi_2026-06")))
                .isEmpty());
        assertTrue(UpdateCheck.findUpdates(
                        List.of("wikipedia_en_all_maxi_2026-06.zim"), List.of(entry("wikipedia_en_all_maxi_2026-02")))
                .isEmpty());
    }

    @Test
    void flavoursAreDistinctBases() {
        // A newer maxi must not be offered as an update to an installed mini.
        assertTrue(UpdateCheck.findUpdates(
                        List.of("wikipedia_en_all_mini_2026-02.zim"), List.of(entry("wikipedia_en_all_maxi_2026-06")))
                .isEmpty());
    }

    @Test
    void undatedInstalledFilesAreNeverFlagged() {
        assertTrue(UpdateCheck.findUpdates(List.of("custom.zim"), List.of(entry("custom_2026-06")))
                .isEmpty());
    }

    @Test
    void newestCatalogBuildWinsWhenSeveralArePresent() {
        List<UpdateCheck.Update> updates = UpdateCheck.findUpdates(
                List.of("vikidia_es_all_2025-01.zim"),
                List.of(entry("vikidia_es_all_2026-03"), entry("vikidia_es_all_2025-11")));
        assertEquals(1, updates.size());
        assertEquals(
                "vikidia_es_all_2026-03.zim", updates.getFirst().replacement().fileName());
    }

    @Test
    void supersedesIsStrictAndConservative() {
        assertTrue(UpdateCheck.supersedes("wiki_en_2026-06.zim", "wiki_en_2026-02.zim"));
        assertFalse(UpdateCheck.supersedes("wiki_en_2026-06.zim", "wiki_en_2026-06.zim"), "itself");
        assertFalse(UpdateCheck.supersedes("wiki_en_2026-02.zim", "wiki_en_2026-06.zim"), "older cannot supersede");
        assertFalse(UpdateCheck.supersedes("wiki_en_2026-06.zim", "wiki_fr_2026-02.zim"), "different base");
        assertFalse(UpdateCheck.supersedes("wiki_en_2026-06.zim", "wiki_en.zim"), "undated is never deleted");
    }
}
