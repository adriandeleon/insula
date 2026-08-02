package com.insula.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the catalog's language codes into words a reader recognises — {@code fra} → French.
 *
 * <p>The feed speaks ISO&nbsp;639-3, and no single JDK call covers it. Two lookups are needed and
 * neither is redundant, which is worth stating because dropping either silently degrades a slice
 * of the catalog back to raw codes:
 *
 * <ul>
 *   <li>The <b>ISO&nbsp;639-3 → 639-1 table</b>, built from {@link Locale#getISOLanguages()},
 *       resolves the codes with a two-letter equivalent: {@code fra}, {@code bod}, {@code zho}.
 *       {@code Locale.forLanguageTag} hands those straight back unchanged.
 *   <li><b>{@code Locale.forLanguageTag}</b> resolves the ones with no two-letter form at all —
 *       {@code ace} (Acehnese), {@code nds} (Low German), {@code mul} — which the table cannot
 *       contain by construction.
 * </ul>
 *
 * <p>Anything neither resolves ({@code ami}, {@code tsz}, …) keeps its code: a wrong name is worse
 * than an honest abbreviation.
 */
public final class LanguageNames {

    /** Past this many codes an entry is about "many languages", not about any of them. */
    static final int MANY = 3;

    private static final Map<String, String> ISO3_TO_ISO1 = buildIso3Map();

    /** Resolved names, since a card renders this on every filter keystroke. */
    private static final Map<String, String> CACHE = new HashMap<>();

    private LanguageNames() {}

    private static Map<String, String> buildIso3Map() {
        Map<String, String> map = new HashMap<>();
        for (String two : Locale.getISOLanguages()) {
            try {
                map.putIfAbsent(Locale.of(two).getISO3Language(), two);
            } catch (RuntimeException ignored) {
                // A code with no ISO3 form simply is not in the table.
            }
        }
        return Map.copyOf(map);
    }

    /**
     * The display form of a catalog language value, which may be one code or a comma-separated
     * list. A list of more than {@link #MANY} becomes a count — some Kiwix entries carry over a
     * hundred codes, and spelling those out would bury the card it labels.
     */
    public static String display(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> codes = new ArrayList<>();
        for (String part : raw.split(",")) {
            String code = part.trim();
            if (!code.isEmpty() && !codes.contains(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return "";
        }
        if (codes.size() > MANY) {
            return codes.size() + " languages";
        }
        List<String> names = codes.stream().map(LanguageNames::one).toList();
        return String.join(", ", names);
    }

    /** One code, resolved as far as the JDK can take it. */
    public static String one(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String key = code.trim().toLowerCase(Locale.ROOT);
        String cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String resolved = resolve(key);
        synchronized (CACHE) {
            CACHE.put(key, resolved);
        }
        return resolved;
    }

    private static String resolve(String code) {
        String two = ISO3_TO_ISO1.get(code);
        if (two != null) {
            String name = Locale.of(two).getDisplayLanguage(Locale.ENGLISH);
            if (!name.isBlank() && !name.equalsIgnoreCase(two)) {
                return name;
            }
        }
        String viaTag = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        // forLanguageTag returns the code itself when it knows nothing, so that is the signal.
        if (!viaTag.isBlank() && !viaTag.equalsIgnoreCase(code)) {
            return viaTag;
        }
        return code;
    }
}
