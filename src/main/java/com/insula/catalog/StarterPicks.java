package com.insula.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Curated first-run suggestions for an empty library.
 *
 * <p>Picks are catalog <em>names</em>, never URLs: each is resolved against the live (cached)
 * catalog at display time, so the size, flavour choice, and mirror list are always the current
 * build's — a hardcoded link would rot the day Kiwix rotates a build date. A name absent from the
 * catalog simply doesn't appear; nothing here may fail the empty-library screen.
 */
public final class StarterPicks {

    private StarterPicks() {}

    /**
     * A curated suggestion: a catalog name, the editorial framing a newcomer scans first
     * ("Try it in 10 seconds"), and the one-line reason they would want it.
     *
     * <p>The label matters because the archive's own name answers "what is this" but not "why
     * would I start here", and on an empty library the second question is the only one being
     * asked.
     */
    public record Pick(String label, String name, String blurb) {}

    /**
     * Ordered smallest-first commitment: a seconds-sized demo, then genuinely useful mid-size
     * archives. All four names verified present on the live Kiwix catalog (2026-08); note
     * wikipedia_en_simple_all is <em>not</em> published there any more, which is exactly why
     * picks resolve by name instead of shipping links.
     */
    public static final List<Pick> PICKS = List.of(
            new Pick(
                    "Try it in 10 seconds",
                    "wikipedia_en_ray-charles",
                    "A tiny slice of Wikipedia. See how reading feels before committing disk space to "
                            + "anything bigger."),
            new Pick("Most popular", "wikipedia_en_top", "Wikipedia's most-read articles, ready anywhere."),
            new Pick("Travel, offline", "wikivoyage_en_all", "Travel guides that work where roaming doesn't."),
            new Pick("For younger readers", "vikidia_en_all", "An encyclopedia written for 8–13 year olds."));

    /** A pick resolved against the catalog: the group plus the variant chosen for this disk. */
    public record Resolved(Pick pick, CatalogGroups.TitleGroup group, ZimEntry entry) {}

    /**
     * Resolves the picks against grouped catalog entries, in {@link #PICKS} order, skipping names
     * the catalog doesn't carry. The variant is the group's default for the given free space.
     */
    public static List<Resolved> resolve(Collection<CatalogGroups.TitleGroup> groups, long freeBytes) {
        List<Resolved> resolved = new ArrayList<>();
        for (Pick pick : PICKS) {
            groups.stream()
                    .filter(g -> g.name().equals(pick.name()))
                    .findFirst()
                    .ifPresent(g -> resolved.add(
                            new Resolved(pick, g, g.defaultVariant(freeBytes).entry())));
        }
        return List.copyOf(resolved);
    }
}
