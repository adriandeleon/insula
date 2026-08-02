package com.insula.catalog;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogFilterTest {

    private static ZimEntry entry(String name, String title, String summary, String lang, String category, long size) {
        return new ZimEntry(
                "id-" + name + lang,
                title,
                summary,
                name,
                "",
                List.of(lang),
                category,
                100,
                0,
                "https://example.org/" + name + "_2026-07.zim.meta4",
                size);
    }

    private static List<CatalogGroups.TitleGroup> groups() {
        return CatalogGroups.group(List.of(
                entry("wikipedia_en_all", "Wikipedia", "The free encyclopedia", "eng", "wikipedia", 100),
                entry("wiktionary_en_all", "Wiktionary", "The free dictionary", "eng", "wiktionary", 50),
                entry("wikipedia_es_all", "Wikipedia", "La enciclopedia libre", "spa", "wikipedia", 90),
                entry("askubuntu", "Ask Ubuntu", "Questions about Ubuntu", "eng", "stack_exchange", 30)));
    }

    @Test
    void noFiltersReturnsEverythingInCatalogOrder() {
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "", Set.of(), "");
        assertEquals(4, result.groups().size());
        assertEquals("wikipedia_en_all", result.groups().getFirst().name());
    }

    @Test
    void queryRanksTitleMatchesAboveSummaryMatches() {
        // "free" appears in two summaries; "wik" prefixes two titles.
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "wik", Set.of(), "");
        assertEquals(3, result.groups().size(), "both Wikipedias and Wiktionary match");
        assertTrue(result.groups().getFirst().title().startsWith("Wik"));

        CatalogFilter.Result summaryHit = CatalogFilter.apply(groups(), "encyclopedia", Set.of(), "");
        assertEquals(1, summaryHit.groups().size(), "summary text is searchable");
    }

    @Test
    void languageFacetFilters() {
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "", Set.of("spa"), "");
        assertEquals(1, result.groups().size());
        assertEquals("wikipedia_es_all", result.groups().getFirst().name());
    }

    @Test
    void categoryFacetFilters() {
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "", Set.of(), "stack_exchange");
        assertEquals(1, result.groups().size());
        assertEquals("askubuntu", result.groups().getFirst().name());
    }

    @Test
    void facetCountsIgnoreTheirOwnDimension() {
        // With only "spa" selected, the language facet must still count "eng" groups —
        // otherwise checking a language would zero out every alternative.
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "", Set.of("spa"), "");
        long engCount = result.languages().stream()
                .filter(f -> f.value().equals("eng"))
                .findFirst()
                .orElseThrow()
                .count();
        assertEquals(3, engCount);
        // ...but the category facet counts only what the language filter lets through.
        long wikipediaCount = result.categories().stream()
                .filter(f -> f.value().equals("wikipedia"))
                .findFirst()
                .orElseThrow()
                .count();
        assertEquals(1, wikipediaCount);
    }

    @Test
    void facetsAreOrderedByCount() {
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "", Set.of(), "");
        assertEquals("eng", result.languages().getFirst().value(), "the biggest language leads");
        for (int i = 1; i < result.languages().size(); i++) {
            assertTrue(result.languages().get(i - 1).count()
                    >= result.languages().get(i).count());
        }
    }

    @Test
    void queryAndFacetsCompose() {
        CatalogFilter.Result result = CatalogFilter.apply(groups(), "wikipedia", Set.of("eng"), "wikipedia");
        assertEquals(1, result.groups().size());
        assertEquals("wikipedia_en_all", result.groups().getFirst().name());
    }

    @Test
    void installedBaseStripsTheBuildDate() {
        assertEquals(
                "wikipedia_en_ray-charles_mini",
                CatalogFilter.installedBaseOf("wikipedia_en_ray-charles_mini_2026-05.zim"));
        assertEquals("wiktionary_es_all", CatalogFilter.installedBaseOf("wiktionary_es_all_2025-03.zim"));
        assertEquals("no_date_name", CatalogFilter.installedBaseOf("no_date_name.zim"));
    }
}
