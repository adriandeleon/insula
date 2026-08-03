package com.insula.library;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * How many columns the shelf uses on a wide window, and which groups go in each.
 *
 * <p>Pure, because the two decisions are judgement calls worth settling once rather than
 * re-arguing inside a layout pass:
 *
 * <ul>
 *   <li><b>When to add a column.</b> A row needs real width to be readable — title, size, build,
 *       verified tick, and three controls — so a column is added only once each would still be
 *       comfortably wide, not merely once two would fit.
 *   <li><b>What to put in them.</b> Whole <em>groups</em>, never the rows inside one. Splitting a
 *       group's rows across columns is the obvious implementation and it looks wrong: a real
 *       library is mostly one- and two-archive groups, so every small group renders a half-width
 *       row beside an empty column, and the page comes out ragged with holes in it. Flowing whole
 *       groups instead keeps every heading with its rows and both columns full.
 * </ul>
 *
 * <p>Groups fill the first column until it is about half the shelf, then the next — so the shelf
 * still reads top-to-bottom, then over. Balancing by picking the emptiest column each time would
 * pack more tightly but scatter the sort order, which is the one thing the shelf's grouping and
 * hand-dragging exist to control.
 */
public final class ShelfColumns {

    /**
     * Below this a row crowds its own controls. Measured rather than guessed: at 620 a 1970px
     * window took three columns and the title, four facts and three buttons were visibly tight.
     */
    public static final double MIN_COLUMN_WIDTH = 780;

    /** More than this and the eye has too far to travel from a title to its buttons. */
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
     * Distributes {@code items} across {@code columns}, in order, keeping each item whole.
     *
     * <p>{@code weight} is how tall an item is <em>on screen</em> — for a group, its heading plus
     * its rows. Counting only the rows makes ten one-archive groups weigh the same as one
     * ten-archive group, when on screen they are twice as tall.
     *
     * @return exactly {@code columns} lists, in reading order; trailing ones may be empty when
     *     there is not enough to fill them
     */
    public static <T> List<List<T>> flow(List<T> items, ToIntFunction<T> weight, int columns) {
        int count = Math.max(1, columns);
        List<List<T>> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new ArrayList<>());
        }
        if (items.isEmpty()) {
            return freeze(out);
        }

        int remaining = 0;
        for (T item : items) {
            remaining += Math.max(1, weight.applyAsInt(item));
        }

        int column = 0;
        int placed = 0;
        int columnsLeft = count;
        int target = ceilDiv(remaining, columnsLeft);
        for (T item : items) {
            int w = Math.max(1, weight.applyAsInt(item));
            // Move on only if this column has something already: an item taller than the target
            // must still land somewhere rather than skipping a column and leaving it empty.
            if (placed > 0 && placed + w > target && columnsLeft > 1) {
                // Re-aim at what is actually left. A fixed target computed once looks right until
                // the last column, which then silently absorbs every remaining group — one run
                // put a single row beside a column of eleven.
                remaining -= placed;
                columnsLeft--;
                column++;
                placed = 0;
                target = ceilDiv(remaining, columnsLeft);
            }
            out.get(column).add(item);
            placed += w;
        }
        return freeze(out);
    }

    private static int ceilDiv(int total, int parts) {
        return Math.max(1, (total + parts - 1) / Math.max(1, parts));
    }

    private static <T> List<List<T>> freeze(List<List<T>> columns) {
        return columns.stream().map(List::copyOf).toList();
    }
}
