package com.insula.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the shelf is arranged: pinning, grouping and sorting — pure, so the fiddly cases are decided
 * by tests rather than by dragging things around.
 *
 * <p>Three rules from the design kit shape this:
 *
 * <ul>
 *   <li><b>Pinned archives sit above every grouping.</b> A star is the whole favourites model, so
 *       it outranks whatever grouping is active rather than sorting within one.
 *   <li><b>Custom is a real sort mode, not a hidden default.</b> Dragging is only offered while
 *       Custom is chosen, and choosing another sort must not discard the arrangement — the stored
 *       order survives untouched so switching back restores it.
 *   <li><b>Themes come from the catalog for free</b> — its category facet maps to friendly names —
 *       with a per-archive override, so nobody is made to tag anything.
 * </ul>
 */
public final class Shelf {

    /** The kit's grouping choices. */
    public enum GroupBy {
        THEME,
        LANGUAGE,
        PUBLISHER,
        NONE
    }

    /** The kit's sort choices. {@link #CUSTOM} is the drag-ordered one. */
    public enum SortBy {
        CUSTOM,
        RECENT,
        NAME,
        SIZE,
        BUILD_DATE
    }

    /** One rendered section: a heading and the archives under it, already sorted. */
    public record Group(String title, List<LibraryEntry> entries) {}

    /** Section title for the pinned archives, which precede every group. */
    public static final String PINNED = "Pinned";

    private Shelf() {}

    /**
     * Arranges entries into display groups. Pinned archives come first in their own section; the
     * rest are grouped and each group sorted. Empty sections are simply absent.
     */
    public static List<Group> arrange(List<LibraryEntry> entries, GroupBy groupBy, SortBy sortBy) {
        List<Group> groups = new ArrayList<>();
        List<LibraryEntry> pinned = entries.stream()
                .filter(LibraryEntry::pinned)
                .sorted(comparator(sortBy))
                .toList();
        if (!pinned.isEmpty()) {
            groups.add(new Group(PINNED, pinned));
        }

        List<LibraryEntry> rest = entries.stream().filter(e -> !e.pinned()).toList();
        if (rest.isEmpty()) {
            return List.copyOf(groups);
        }
        if (groupBy == GroupBy.NONE) {
            groups.add(new Group(
                    "All archives", rest.stream().sorted(comparator(sortBy)).toList()));
            return List.copyOf(groups);
        }

        Map<String, List<LibraryEntry>> byKey = new LinkedHashMap<>();
        for (LibraryEntry entry : rest) {
            byKey.computeIfAbsent(keyFor(entry, groupBy), k -> new ArrayList<>())
                    .add(entry);
        }
        byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(unknownLast()))
                .forEach(e -> groups.add(new Group(
                        e.getKey(),
                        e.getValue().stream().sorted(comparator(sortBy)).toList())));
        return List.copyOf(groups);
    }

    /** "Other" sorts last whatever it is called, so the catch-all never heads the shelf. */
    private static Comparator<String> unknownLast() {
        return Comparator.comparing((String key) -> key.equals(UNGROUPED)).thenComparing(Comparator.naturalOrder());
    }

    static final String UNGROUPED = "Other";

    private static String keyFor(LibraryEntry entry, GroupBy groupBy) {
        String value =
                switch (groupBy) {
                    case THEME -> !entry.theme().isBlank() ? entry.theme() : themeOf(entry.fileName());
                    case LANGUAGE -> languageOf(entry.fileName());
                    case PUBLISHER -> publisherOf(entry.fileName());
                    case NONE -> "";
                };
        return value == null || value.isBlank() ? UNGROUPED : value;
    }

    public static Comparator<LibraryEntry> comparator(SortBy sortBy) {
        return switch (sortBy) {
            // Custom is the stored order; ties fall back to name so a fresh library (every order 0)
            // is still stable rather than arbitrary.
            case CUSTOM ->
                Comparator.comparingInt(LibraryEntry::order)
                        .thenComparing(LibraryEntry::title, String.CASE_INSENSITIVE_ORDER);
            case RECENT ->
                Comparator.comparingLong(LibraryEntry::addedAtEpochMs)
                        .reversed()
                        .thenComparing(LibraryEntry::title, String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(LibraryEntry::title, String.CASE_INSENSITIVE_ORDER);
            case SIZE ->
                Comparator.comparingLong(LibraryEntry::sizeBytes)
                        .reversed()
                        .thenComparing(LibraryEntry::title, String.CASE_INSENSITIVE_ORDER);
            case BUILD_DATE ->
                Comparator.comparing(Shelf::buildDateOf)
                        .reversed()
                        .thenComparing(LibraryEntry::title, String.CASE_INSENSITIVE_ORDER);
        };
    }

    /**
     * Re-numbers a hand-dragged arrangement. Every entry is renumbered from zero, so the stored
     * order stays dense and a later insert cannot collide with an existing position.
     */
    public static List<LibraryEntry> reorder(List<LibraryEntry> ordered) {
        List<LibraryEntry> renumbered = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            renumbered.add(ordered.get(i).withOrder(i));
        }
        return List.copyOf(renumbered);
    }

    /** The {@code YYYY-MM} build stamp, or {@code ""} — the same convention updates use. */
    static String buildDateOf(LibraryEntry entry) {
        var matcher = java.util.regex.Pattern.compile("_(\\d{4}-\\d{2})(?:\\.zim)?$")
                .matcher(
                        entry.fileName().endsWith(".zim")
                                ? entry.fileName().substring(0, entry.fileName().length() - 4)
                                : entry.fileName());
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * A friendly theme from the archive's own file name, which encodes the catalog's project —
     * {@code wikipedia_en_all_mini} → Wikipedia. Free, because every Kiwix name starts this way.
     */
    public static String themeOf(String fileName) {
        String project = firstToken(fileName);
        return switch (project) {
            case "wikipedia",
                    "wikibooks",
                    "wikisource",
                    "wikiversity",
                    "wikivoyage",
                    "wiktionary",
                    "wikinews",
                    "wikiquote" -> "Encyclopedias & reference";
            case "gutenberg", "bookdash", "storyweaver" -> "Books";
            case "ted", "crashcourse", "khanacademy", "openstax" -> "Courses & talks";
            case "stackoverflow", "askubuntu", "superuser", "serverfault", "stackexchange" -> "Q&A";
            case "" -> "";
            default -> capitalize(project);
        };
    }

    /** The language token Kiwix puts second: {@code wikipedia_en_all} → "en". */
    public static String languageOf(String fileName) {
        String[] parts = base(fileName).split("_");
        return parts.length >= 2 ? parts[1] : "";
    }

    /** The publisher is the project token, shown as itself: {@code ted_mul_tech} → "TED". */
    public static String publisherOf(String fileName) {
        String project = firstToken(fileName);
        return project.isEmpty()
                ? ""
                : project.toUpperCase(Locale.ROOT).length() <= 3
                        ? project.toUpperCase(Locale.ROOT)
                        : capitalize(project);
    }

    private static String firstToken(String fileName) {
        String base = base(fileName);
        int underscore = base.indexOf('_');
        return (underscore < 0 ? base : base.substring(0, underscore)).toLowerCase(Locale.ROOT);
    }

    private static String base(String fileName) {
        return fileName.endsWith(".zim") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
