package com.insula.library;

import java.util.List;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the shelf earns a second column, and which groups land in it. */
class ShelfColumnsTest {

    private static final double GAP = 14;

    /** A group of {@code size} archives; the int is what the flow weighs it by. */
    private static final ToIntFunction<Integer> SIZE = i -> i;

    @Test
    void anOrdinaryWindowStaysOneColumn() {
        assertEquals(1, ShelfColumns.columnsFor(900, GAP));
        assertEquals(1, ShelfColumns.columnsFor(1200, GAP));
        assertEquals(1, ShelfColumns.columnsFor(1500, GAP), "a laptop full-screen is still one");
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
        assertEquals(ShelfColumns.MAX_COLUMNS, ShelfColumns.columnsFor(10_000, GAP));
    }

    @Test
    void aDegenerateWidthIsStillOneColumn() {
        assertEquals(1, ShelfColumns.columnsFor(0, GAP));
        assertEquals(1, ShelfColumns.columnsFor(-50, GAP));
    }

    @Test
    void groupsFlowDownTheFirstColumnThenOverSoTheOrderStillReads() {
        // 12 rows over 2 columns: six each, and the split falls on a group boundary.
        assertEquals(List.of(List.of(3, 3), List.of(3, 3)), ShelfColumns.flow(List.of(3, 3, 3, 3), SIZE, 2));
    }

    @Test
    void aGroupIsNeverSplitAcrossColumns() {
        // The whole reason this exists: splitting a group's rows leaves every one-archive group
        // as a half-width row beside an empty column.
        List<List<Integer>> columns = ShelfColumns.flow(List.of(1, 1, 6, 1), SIZE, 2);
        assertTrue(
                columns.stream().flatMap(List::stream).toList().equals(List.of(1, 1, 6, 1)),
                "every group intact and in order: " + columns);
    }

    @Test
    void weightIsRowsNotGroups() {
        // Otherwise a column of ten headings sits beside one heading and nine rows.
        List<List<Integer>> columns = ShelfColumns.flow(List.of(1, 1, 1, 1, 8), SIZE, 2);
        assertEquals(List.of(1, 1, 1, 1), columns.get(0));
        assertEquals(List.of(8), columns.get(1));
    }

    @Test
    void everyGroupLandsInExactlyOneColumnInOrder() {
        List<Integer> groups = List.of(1, 4, 1, 2, 6, 1, 1, 3);
        for (int columns = 1; columns <= ShelfColumns.MAX_COLUMNS; columns++) {
            List<Integer> flattened = ShelfColumns.flow(groups, SIZE, columns).stream()
                    .flatMap(List::stream)
                    .toList();
            assertEquals(groups, flattened, "columns=" + columns);
        }
    }

    @Test
    void theLastColumnIsNotTheOverloadedOne() {
        // A long tail at the bottom right reads as a mistake rather than a layout.
        List<List<Integer>> columns = ShelfColumns.flow(List.of(1, 1, 1, 1, 1, 1, 1), SIZE, 2);
        assertTrue(weight(columns.get(0)) >= weight(columns.get(1)), "columns " + columns);
    }

    @Test
    void noColumnIsLeftNearlyEmptyBesideAnOverstuffedOne() {
        // The real shape of a library: a few big groups among many one-archive ones. A target
        // computed once and never revised put a single row beside a column of eleven.
        List<Integer> groups = List.of(2, 2, 2, 4, 2, 7, 2, 2, 2, 2, 2);
        List<List<Integer>> columns = ShelfColumns.flow(groups, SIZE, 3);
        int lightest = columns.stream().mapToInt(ShelfColumnsTest::weight).min().orElseThrow();
        int heaviest = columns.stream().mapToInt(ShelfColumnsTest::weight).max().orElseThrow();
        assertTrue(heaviest - lightest <= 7, "columns " + columns + " differ by " + (heaviest - lightest));
    }

    private static int weight(List<Integer> column) {
        return column.stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void oneGroupTallerThanAColumnStillLandsSomewhere() {
        List<List<Integer>> columns = ShelfColumns.flow(List.of(40), SIZE, 3);
        assertEquals(List.of(40), columns.get(0));
        assertTrue(columns.get(1).isEmpty() && columns.get(2).isEmpty());
    }

    @Test
    void fewerGroupsThanColumnsLeavesTheExtrasEmptyRatherThanMissing() {
        assertEquals(3, ShelfColumns.flow(List.of(1, 1), SIZE, 3).size());
        assertEquals(2, ShelfColumns.flow(List.of(), SIZE, 2).size());
    }
}
