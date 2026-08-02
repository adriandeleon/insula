package com.insula.catalog;

import java.util.List;

/**
 * Which slice of a filtered catalog is on screen.
 *
 * <p>Pure, because the failure everyone hits is not the arithmetic but the <em>stale page</em>:
 * narrow a filter while on page 30 and a naive pager shows an empty grid over a non-empty result,
 * which reads as "nothing matches" when plenty does. {@link #clamp} is what stops that, and it is
 * only correct if every caller runs it after every filter change — hence a named function rather
 * than an inline {@code Math.min}.
 */
public final class Paging {

    /** Enough to fill a window without making the grid a scroll marathon. */
    public static final int PAGE_SIZE = 24;

    private Paging() {}

    /** Pages needed for {@code total} items; always at least one, so "Page 1 of 1" is truthful. */
    public static int pageCount(int total, int pageSize) {
        if (pageSize <= 0) {
            return 1;
        }
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    /** The nearest valid page to {@code page} — the fix for a filter that shrank under you. */
    public static int clamp(int page, int total, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(total, pageSize) - 1));
    }

    /** The items on {@code page}, clamped first so an out-of-range page shows the last one. */
    public static <T> List<T> slice(List<T> items, int page, int pageSize) {
        if (items.isEmpty() || pageSize <= 0) {
            return List.of();
        }
        int safe = clamp(page, items.size(), pageSize);
        int from = safe * pageSize;
        return List.copyOf(items.subList(from, Math.min(items.size(), from + pageSize)));
    }

    /** "Page 2 of 47 · 1,113 archives" — the label under the grid. */
    public static String label(int page, int total, int pageSize) {
        int pages = pageCount(total, pageSize);
        String count = String.format("%,d", total) + (total == 1 ? " archive" : " archives");
        return pages == 1 ? count : "Page " + (clamp(page, total, pageSize) + 1) + " of " + pages + " · " + count;
    }
}
