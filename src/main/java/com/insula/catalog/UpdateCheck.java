package com.insula.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds newer catalog builds for installed archives.
 *
 * <p>Identity is the file-name base — name plus flavour with the {@code _YYYY-MM} build date
 * stripped ({@link StoreFilter#installedBaseOf}) — because that is the only key present on both
 * sides: the library knows only what file it has, and the catalog always carries exactly one
 * (the newest) build per base. Freshness compares the {@code YYYY-MM} stamps lexically, which is
 * chronological by construction. A file without a parseable date is never flagged: guessing
 * "newer" from anything else (size, mtime) would nag people about files that are current.
 */
public final class UpdateCheck {

    private static final Pattern BUILD_DATE = Pattern.compile("_(\\d{4}-\\d{2})(?:\\.zim)?$");

    private UpdateCheck() {}

    /** An installed file for which the catalog offers a newer build. */
    public record Update(String installedFileName, ZimEntry replacement) {}

    /** The {@code YYYY-MM} build stamp of a ZIM file name, or {@code ""} when it has none. */
    public static String buildDateOf(String fileName) {
        String base = fileName.endsWith(".zim") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Matcher m = BUILD_DATE.matcher(base);
        return m.find() ? m.group(1) : "";
    }

    /**
     * All installed files the catalog can upgrade, ordered by installed file name. Each base maps
     * to at most one update — the newest dated catalog build for that base.
     */
    public static List<Update> findUpdates(Collection<String> installedFileNames, Collection<ZimEntry> catalog) {
        Map<String, ZimEntry> newestByBase = new HashMap<>();
        for (ZimEntry entry : catalog) {
            String date = buildDateOf(entry.fileName());
            if (date.isEmpty()) {
                continue;
            }
            newestByBase.merge(
                    StoreFilter.installedBaseOf(entry.fileName()),
                    entry,
                    (a, b) -> buildDateOf(a.fileName()).compareTo(buildDateOf(b.fileName())) >= 0 ? a : b);
        }

        List<Update> updates = new ArrayList<>();
        for (String installed : installedFileNames.stream().sorted().toList()) {
            String installedDate = buildDateOf(installed);
            if (installedDate.isEmpty()) {
                continue;
            }
            ZimEntry newest = newestByBase.get(StoreFilter.installedBaseOf(installed));
            if (newest != null && buildDateOf(newest.fileName()).compareTo(installedDate) > 0) {
                updates.add(new Update(installed, newest));
            }
        }
        return List.copyOf(updates);
    }

    /**
     * Whether a freshly arrived file makes an older installed one redundant: same base, both
     * dated, and the old one strictly older. Undated files are never called superseded — that
     * judgement must stay conservative, since the caller offers to <em>delete</em> them.
     */
    public static boolean supersedes(String newFileName, String oldFileName) {
        if (newFileName.equals(oldFileName)) {
            return false;
        }
        String newDate = buildDateOf(newFileName);
        String oldDate = buildDateOf(oldFileName);
        return !newDate.isEmpty()
                && !oldDate.isEmpty()
                && StoreFilter.installedBaseOf(newFileName).equals(StoreFilter.installedBaseOf(oldFileName))
                && oldDate.compareTo(newDate) < 0;
    }
}
