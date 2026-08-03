package com.insula.library;

import java.util.ArrayList;
import java.util.List;

/**
 * How many columns the shelf should use, and how a group's rows split between them.
 *
 * <p>Pure, because the two interesting decisions are both judgement calls that should be settled
 * once and testable rather than re-argued inside a layout pass:
 *
 * <ul>
 *   <li><b>When to add a column.</b> A row needs a real width to be readable — title, size, build,
 *       verified tick, and three controls — so columns are added only once each would still be
 *       comfortably wide, not merely possible.
 *   <li><b>How to split.</b> Rows fill <em>down</em> each column, not across, so the shelf still
 *       reads top-to-bottom in the order the sort produced. Filling across would make a
 *       drag-ordered list read as a zigzag.
 * </ul>
 */
public final class ShelfColumns {

    /** Below this a row starts crowding its own controls. */
    public static final double MIN_COLUMN_WIDTH = 620;

    /** More than this and the eye has too far to travel between a title and its buttons. */
    public static final int MAX_COLUMNS = 3;

    private ShelfColumns() {}

    /** Columns that fit in {@code availableWidth}, allowing {@code gap} between them. */
    public static int columnsFor(double availableWidth, double gap) {
        if (availableWidth <= 0) {
            return 1;
        }
        int columns = 1;
        while (columns < MAX_COLUMNS && (columns + 1) * MIN_COLUMN_WIDTH + columns * gap <= availableWidth) {
            columns++;
        }
        return columns;
    }

    /**
     * Splits {@code rows} into {@code columns} lists, filling each column top to bottom.
     *
     * <p>Earlier columns take the remainder, so a 7-row group across 2 columns is 4 and 3 — never
     * 3 and 4, which would leave a ragged gap on the left where the eye starts.
     */
    public static <T> List<List<T>> split(List<T> rows, int columns) {
        int count = Math.max(1, columns);
        List<List<T>> out = new ArrayList<>(count);
        if (rows.isEmpty()) {
            for (int i = 0; i < count; i++) {
                out.add(List.of());
            }
            return out;
        }
        int perColumn = rows.size() / count;
        int remainder = rows.size() % count;
        int from = 0;
        for (int i = 0; i < count; i++) {
            int take = perColumn + (i < remainder ? 1 : 0);
            out.add(List.copyOf(rows.subList(from, from + take)));
            from += take;
        }
        return out;
    }
}
