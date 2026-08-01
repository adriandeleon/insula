package com.insula.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.insula.zim.Dirent;
import com.insula.zim.ZimArchive;

/**
 * Every article title of one archive, held in memory for fuzzy scanning.
 *
 * <p>Fuzzy matching cannot use the on-disk title index: that is sorted for prefix lookup, and
 * scoring every entry would mean re-reading every dirent on each keystroke. Measured on a
 * 192k-entry archive that walk costs ~350 ms — fine once, hopeless per keypress. So it is paid
 * once, off the FX thread, and the result (about 6 MB of titles for that archive) is scanned in
 * memory thereafter.
 *
 * <p>Titles are stored pre-lower-cased alongside their original, because lower-casing 192k strings
 * per keystroke would dominate the search itself.
 */
public final class TitleIndex {

    /** Entries above this are indexed up to the cap; a partial index beats an unusable one. */
    static final int MAX_ENTRIES = 2_000_000;

    private final String[] titles;
    private final String[] lowerTitles;
    private final String[] paths;

    private TitleIndex(String[] titles, String[] lowerTitles, String[] paths) {
        this.titles = titles;
        this.lowerTitles = lowerTitles;
        this.paths = paths;
    }

    /** Walks every dirent once. Call off the FX thread. */
    public static TitleIndex build(ZimArchive archive) throws IOException {
        char content = archive.contentNamespace();
        List<String> titles = new ArrayList<>();
        List<String> lower = new ArrayList<>();
        List<String> paths = new ArrayList<>();

        long limit = Math.min(archive.entryCount(), MAX_ENTRIES);
        for (long i = 0; i < limit; i++) {
            Dirent d = archive.direntAt(i);
            // Only article-namespace entries: the user is searching for something to read, not
            // for metadata, stylesheets or the search index blobs.
            if (d.namespace() != content || d.title().isEmpty()) {
                continue;
            }
            titles.add(d.title());
            lower.add(d.title().toLowerCase(Locale.ROOT));
            paths.add(d.fullPath());
        }
        return new TitleIndex(
                titles.toArray(String[]::new), lower.toArray(String[]::new), paths.toArray(String[]::new));
    }

    public int size() {
        return titles.length;
    }

    /**
     * Scores every title and keeps the best {@code limit}. Uses a running threshold rather than
     * sorting all matches: on a large archive a common prefix can match tens of thousands of
     * entries, and sorting those to show ten is wasted work on the keystroke path.
     */
    public List<Hit> search(String loweredQuery, int limit) {
        if (loweredQuery.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Hit> best = new ArrayList<>(limit + 1);
        int worstKept = MatchScore.NO_MATCH;

        for (int i = 0; i < lowerTitles.length; i++) {
            int score = MatchScore.score(loweredQuery, lowerTitles[i], titles[i].length());
            if (score == MatchScore.NO_MATCH || (best.size() >= limit && score <= worstKept)) {
                continue;
            }
            insertSorted(best, new Hit(titles[i], paths[i], score), limit);
            worstKept = best.get(best.size() - 1).score();
        }
        return List.copyOf(best);
    }

    private static void insertSorted(List<Hit> best, Hit hit, int limit) {
        int at = 0;
        while (at < best.size() && best.get(at).score() >= hit.score()) {
            at++;
        }
        best.add(at, hit);
        if (best.size() > limit) {
            best.remove(best.size() - 1);
        }
    }

    /** One result within a single archive. */
    public record Hit(String title, String fullPath, int score) {}
}
