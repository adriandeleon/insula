package com.insula.library;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** When the shelf earns a second column, and how rows divide between them. */
class ShelfColumnsTest {

    private static final double GAP = 14;

    @Test
    void anOrdinaryWindowStaysOneColumn() {
        assertEquals(1, ShelfColumns.columnsFor(900, GAP));
        assertEquals(1, ShelfColumns.columnsFor(1200, GAP));
    }

    @Test
    void aColumnIsAddedOnlyWhenBothWouldStillBeComfortable() {
        // Not merely when two fit: a row carries a title, three facts and three controls.
        double justUnder = 2 * ShelfColumns.MIN_COLUMN_WIDTH + GAP - 1;
        assertEquals(1, ShelfColumns.columnsFor(justUnder, GAP));
        assertEquals(2, ShelfColumns.columnsFor(justUnder + 1, GAP));
    }

    @Test
    void anUltrawideDoesNotKeepAddingColumnsForever() {
        // Past a point the eye has too far to travel from a title to its buttons.
        assertEquals(ShelfColumns.MAX_COLUMNS, ShelfColumns.columnsFor(10_000, GAP));
    }

    @Test
    void aDegenerateWidthIsStillOneColumn() {
        assertEquals(1, ShelfColumns.columnsFor(0, GAP));
        assertEquals(1, ShelfColumns.columnsFor(-50, GAP));
    }

    @Test
    void rowsFillDownEachColumnSoTheSortOrderStillReadsTopToBottom() {
        // Filling across would turn a hand-dragged order into a zigzag.
        assertEquals(List.of(List.of(1, 2, 3), List.of(4, 5, 6)), ShelfColumns.split(List.of(1, 2, 3, 4, 5, 6), 2));
    }

    @Test
    void theRemainderGoesToTheEarlierColumn() {
        // 4 and 3, never 3 and 4: a ragged gap on the left is where the eye starts.
        assertEquals(
                List.of(List.of(1, 2, 3, 4), List.of(5, 6, 7)), ShelfColumns.split(List.of(1, 2, 3, 4, 5, 6, 7), 2));
    }

    @Test
    void everyRowLandsInExactlyOneColumn() {
        List<Integer> rows = java.util.stream.IntStream.range(0, 17).boxed().toList();
        for (int columns = 1; columns <= ShelfColumns.MAX_COLUMNS; columns++) {
            List<Integer> flattened = ShelfColumns.split(rows, columns).stream()
                    .flatMap(List::stream)
                    .toList();
            assertEquals(rows, flattened, "columns=" + columns);
        }
    }

    @Test
    void fewerRowsThanColumnsLeavesTheExtrasEmptyRatherThanMissing() {
        assertEquals(List.of(List.of(1), List.of(2), List.of()), ShelfColumns.split(List.of(1, 2), 3));
        assertEquals(List.of(List.of(), List.of()), ShelfColumns.split(List.of(), 2));
    }
}
