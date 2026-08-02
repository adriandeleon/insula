package com.insula.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.insula.search.MatchScore;

/**
 * Filters and ranks title groups for the Catalog: query × language facet × category facet.
 *
 * <p>Pure, and runs entirely against the in-memory group list — this is what "search is local and
 * instant" means in practice. Facet counts are computed under the <em>other</em> active filters,
 * so a language with zero matches under the current query greys out instead of lying.
 */
public final class CatalogFilter {

    private CatalogFilter() {}

    /** A facet value with the number of groups it would match right now. */
    public record Facet(String value, long count) {}

    public record Result(List<CatalogGroups.TitleGroup> groups, List<Facet> languages, List<Facet> categories) {}

    /**
     * @param selectedLanguages ISO-639-3 codes; empty = no language filter
     * @param selectedCategory a category name, or {@code ""} for all
     */
    public static Result apply(
            List<CatalogGroups.TitleGroup> all, String query, Set<String> selectedLanguages, String selectedCategory) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        List<CatalogGroups.TitleGroup> afterQuery = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (CatalogGroups.TitleGroup group : all) {
            int score = score(group, q);
            if (score != MatchScore.NO_MATCH) {
                afterQuery.add(group);
                scores.add(score);
            }
        }

        // Facet counts each ignore their own dimension so a checked value never zeroes itself.
        Map<String, Long> languageCounts = new LinkedHashMap<>();
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        List<CatalogGroups.TitleGroup> matched = new ArrayList<>();
        List<CatalogGroups.TitleGroup> ranked = new ArrayList<>();

        for (int i = 0; i < afterQuery.size(); i++) {
            CatalogGroups.TitleGroup group = afterQuery.get(i);
            boolean languageOk = matchesLanguage(group, selectedLanguages);
            boolean categoryOk = matchesCategory(group, selectedCategory);
            if (categoryOk) {
                for (String lang : languagesOf(group)) {
                    languageCounts.merge(lang, 1L, Long::sum);
                }
            }
            if (languageOk) {
                categoryCounts.merge(categoryOf(group), 1L, Long::sum);
            }
            if (languageOk && categoryOk) {
                matched.add(group);
            }
        }

        // Rank by query score (best first), stable by title. No query = catalog order.
        if (!q.isEmpty()) {
            record Scored(CatalogGroups.TitleGroup group, int score) {}
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < afterQuery.size(); i++) {
                if (matched.contains(afterQuery.get(i))) {
                    scored.add(new Scored(afterQuery.get(i), scores.get(i)));
                }
            }
            scored.sort(Comparator.comparingInt(Scored::score)
                    .reversed()
                    .thenComparing(s -> s.group().title()));
            scored.forEach(s -> ranked.add(s.group()));
        } else {
            ranked.addAll(matched);
        }

        return new Result(List.copyOf(ranked), toFacets(languageCounts), toFacets(categoryCounts));
    }

    private static int score(CatalogGroups.TitleGroup group, String query) {
        if (query.isEmpty()) {
            return 1;
        }
        int best = MatchScore.score(
                query, group.title().toLowerCase(Locale.ROOT), group.title().length());
        best = Math.max(
                best,
                MatchScore.score(
                        query,
                        group.name().toLowerCase(Locale.ROOT),
                        group.name().length()));
        if (best == MatchScore.NO_MATCH
                && group.summary().toLowerCase(Locale.ROOT).contains(query)) {
            best = 1; // summary matches rank below any title/name match
        }
        return best;
    }

    static List<String> languagesOf(CatalogGroups.TitleGroup group) {
        return group.variants().getFirst().entry().languages();
    }

    static String categoryOf(CatalogGroups.TitleGroup group) {
        return group.category() == null || group.category().isBlank() ? "other" : group.category();
    }

    private static boolean matchesLanguage(CatalogGroups.TitleGroup group, Set<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }
        return languagesOf(group).stream().anyMatch(selected::contains);
    }

    private static boolean matchesCategory(CatalogGroups.TitleGroup group, String selected) {
        return selected == null || selected.isBlank() || categoryOf(group).equals(selected);
    }

    private static List<Facet> toFacets(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(e -> new Facet(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Facet::count).reversed().thenComparing(Facet::value))
                .toList();
    }

    /** Strips the {@code _YYYY-MM} build date (and extension) so installed files match catalog entries. */
    public static String installedBaseOf(String fileName) {
        String base = fileName;
        if (base.endsWith(".zim")) {
            base = base.substring(0, base.length() - 4);
        }
        return base.replaceFirst("_\\d{4}-\\d{2}$", "");
    }
}
