package com.insula.download;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Works out which Metalink mirrors a {@code .torrent} is missing as web seeds (BEP 19).
 *
 * <p>Kiwix's torrents carry only a handful of mirrors while the {@code .meta4} for the same file
 * lists more. Measured on {@code wikipedia_en_ray-charles_mini_2026-05}: the torrent had 4 web
 * seeds, all North American, while the Metalink listed 9 including Netherlands, France and
 * Denmark — so 5 of 9 were absent. That gap matters most for the users furthest from the US
 * mirrors, and a thin swarm (many Kiwix files have one or two seeders, sometimes none) means web
 * seeds are frequently the only thing moving bytes at all.
 *
 * <p>Pure so the merge rules are unit-tested without a libtorrent session.
 */
public final class WebSeeds {

    private WebSeeds() {}

    /**
     * Mirrors present in the Metalink but not already advertised by the torrent.
     *
     * <p>Comparison ignores a trailing slash and is case-insensitive on scheme and host only —
     * mirror paths are case-sensitive on the server. Adding a duplicate would be harmless to
     * libtorrent but would misreport how much the merge actually contributed.
     */
    public static List<String> missingFrom(List<String> torrentWebSeeds, List<String> metalinkMirrors) {
        Set<String> existing = new LinkedHashSet<>();
        for (String url : nullToEmpty(torrentWebSeeds)) {
            existing.add(canonical(url));
        }
        List<String> missing = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(existing);
        for (String url : nullToEmpty(metalinkMirrors)) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String key = canonical(url);
            if (seen.add(key)) {
                missing.add(url.strip());
            }
        }
        return List.copyOf(missing);
    }

    /**
     * A web seed URL must address the same file the torrent describes. A mirror whose path does
     * not end in the torrent's file name is dropped rather than handed to libtorrent, which would
     * otherwise fetch the wrong bytes and fail every piece it built from them.
     */
    public static List<String> matchingFile(List<String> mirrors, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return List.copyOf(nullToEmpty(mirrors));
        }
        List<String> ok = new ArrayList<>();
        for (String url : nullToEmpty(mirrors)) {
            if (url != null && stripQuery(url).endsWith("/" + fileName)) {
                ok.add(url.strip());
            }
        }
        return List.copyOf(ok);
    }

    private static String canonical(String url) {
        if (url == null) {
            return "";
        }
        String text = stripQuery(url.strip());
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        int schemeEnd = text.indexOf("://");
        if (schemeEnd < 0) {
            return text;
        }
        int pathStart = text.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return text.toLowerCase(Locale.ROOT);
        }
        // scheme + host lower-cased, path left alone
        return text.substring(0, pathStart).toLowerCase(Locale.ROOT) + text.substring(pathStart);
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
