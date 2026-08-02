package com.insula.config;

import java.util.ArrayList;
import java.util.List;

/**
 * A most-recently-used list of paths, kept in a single settings string.
 *
 * <p>Pure, because the interesting parts are all edge cases: re-opening something already in the
 * list must move it rather than duplicate it, the list must not grow without bound, and it has to
 * survive a hand-edited or half-written properties file. None of that is worth discovering by
 * clicking.
 *
 * <p>Entries are stored newline-separated — a path may legally contain almost anything else,
 * including the separators one would reach for first (comma, semicolon, colon), and a Java
 * properties value cannot contain a raw newline, so the encoder escapes it. Blank entries are
 * dropped on read rather than trusted.
 */
public final class RecentList {

    /** Long enough to cover "the ones I actually switch between", short enough to scan. */
    public static final int MAX = 8;

    private static final String SEPARATOR = "\n";

    private RecentList() {}

    /** The list with {@code value} at the front, de-duplicated and capped. */
    public static List<String> promote(List<String> current, String value) {
        if (value == null || value.isBlank()) {
            return List.copyOf(current);
        }
        List<String> next = new ArrayList<>();
        next.add(value);
        for (String existing : current) {
            if (existing != null && !existing.isBlank() && !existing.equals(value) && next.size() < MAX) {
                next.add(existing);
            }
        }
        return List.copyOf(next);
    }

    public static List<String> remove(List<String> current, String value) {
        return current.stream()
                .filter(e -> e != null && !e.isBlank() && !e.equals(value))
                .toList();
    }

    public static String encode(List<String> entries) {
        return String.join(SEPARATOR, entries);
    }

    public static List<String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(SEPARATOR, -1)) {
            if (!part.isBlank() && out.size() < MAX) {
                out.add(part);
            }
        }
        return List.copyOf(out);
    }
}
