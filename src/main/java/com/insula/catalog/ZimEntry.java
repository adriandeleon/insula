package com.insula.catalog;

import java.util.List;

/**
 * One downloadable archive from the Kiwix OPDS catalog.
 *
 * <p>The catalog's acquisition link points at the <b>{@code .meta4}</b> Metalink, not the raw
 * {@code .zim} (verified across the live catalog: 200/200 entries). {@link #zimUrl()} strips that
 * suffix and the other sidecars derive from it, so nothing here hardcodes a mirror or a second
 * index.
 *
 * @param id catalog uuid ({@code urn:uuid:…})
 * @param title human title, e.g. "Klexikon – das Kinderlexikon"
 * @param name archive base name, e.g. {@code wikipedia_en_100}
 * @param flavour {@code ""}, {@code mini}, {@code nopic} or {@code maxi}
 * @param languages ISO-639-3 codes, in catalog order (an archive may carry many)
 * @param metalinkUrl the acquisition link exactly as published
 * @param sizeBytes the acquisition link's {@code length}
 * @param updated the entry's {@code <updated>} timestamp as published (ISO-8601), or {@code ""}
 * @param illustrationHref the 48px illustration link href as published (usually relative to the
 *     catalog host), or {@code ""} when the entry has none
 */
public record ZimEntry(
        String id,
        String title,
        String summary,
        String name,
        String flavour,
        List<String> languages,
        String category,
        long articleCount,
        long mediaCount,
        String metalinkUrl,
        long sizeBytes,
        String updated,
        String illustrationHref) {

    private static final String METALINK_SUFFIX = ".meta4";

    public ZimEntry {
        languages = languages == null ? List.of() : List.copyOf(languages);
        updated = updated == null ? "" : updated;
        illustrationHref = illustrationHref == null ? "" : illustrationHref;
    }

    /** Convenience for callers (mostly tests) that do not care about date or illustration. */
    public ZimEntry(
            String id,
            String title,
            String summary,
            String name,
            String flavour,
            List<String> languages,
            String category,
            long articleCount,
            long mediaCount,
            String metalinkUrl,
            long sizeBytes) {
        this(
                id,
                title,
                summary,
                name,
                flavour,
                languages,
                category,
                articleCount,
                mediaCount,
                metalinkUrl,
                sizeBytes,
                "",
                "");
    }

    /**
     * The comparable date portion of {@link #updated()} ({@code yyyy-MM-dd}), or {@code ""}.
     * ISO-8601 dates compare correctly as strings, which is all update detection needs.
     */
    public String updatedDate() {
        return updated.length() >= 10 ? updated.substring(0, 10) : updated;
    }

    /** The archive itself — the metalink URL minus its {@code .meta4} suffix. */
    public String zimUrl() {
        return metalinkUrl.endsWith(METALINK_SUFFIX)
                ? metalinkUrl.substring(0, metalinkUrl.length() - METALINK_SUFFIX.length())
                : metalinkUrl;
    }

    public String sha256Url() {
        return zimUrl() + ".sha256";
    }

    public String torrentUrl() {
        return zimUrl() + ".torrent";
    }

    public String magnetUrl() {
        return zimUrl() + ".magnet";
    }

    /** The on-disk file name, e.g. {@code wikipedia_en_100_maxi_2026-07.zim}. */
    public String fileName() {
        String url = zimUrl();
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }

    /** Title plus flavour, for a list row: "Klexikon – das Kinderlexikon (nopic)". */
    public String displayName() {
        return flavour == null || flavour.isBlank() ? title : title + " (" + flavour + ")";
    }
}
