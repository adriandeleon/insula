package com.insula.catalog;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paging the catalog grid. */
class PagingTest {

    private static List<Integer> items(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void aPartialLastPageStillCounts() {
        assertEquals(3, Paging.pageCount(25, 10));
        assertEquals(2, Paging.pageCount(20, 10));
    }

    @Test
    void thereIsAlwaysAtLeastOnePageSoTheLabelIsTruthful() {
        assertEquals(1, Paging.pageCount(0, 10));
        assertEquals("0 archives", Paging.label(0, 0, 10));
    }

    @Test
    void aPageLeftOverFromAWiderFilterIsClampedRatherThanShowingNothing() {
        // Narrow a filter while on page 30 and the naive pager renders an empty grid over a
        // non-empty result — which reads as "nothing matches" when plenty does.
        assertEquals(1, Paging.clamp(30, 20, 10));
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), Paging.slice(items(20), 30, 10));
    }

    @Test
    void aNegativePageIsTheFirstOne() {
        assertEquals(0, Paging.clamp(-5, 100, 10));
        assertEquals(List.of(0, 1), Paging.slice(items(50), -1, 2));
    }

    @Test
    void theLastPageIsWhateverIsLeft() {
        assertEquals(List.of(20, 21, 22, 23, 24), Paging.slice(items(25), 2, 10));
    }

    @Test
    void everyItemAppearsExactlyOnceAcrossThePages() {
        // The property that matters: paging must partition, not sample.
        List<Integer> all = items(97);
        List<Integer> seen = new java.util.ArrayList<>();
        for (int p = 0; p < Paging.pageCount(all.size(), 10); p++) {
            seen.addAll(Paging.slice(all, p, 10));
        }
        assertEquals(all, seen);
    }

    @Test
    void anEmptyResultPagesToNothing() {
        assertTrue(Paging.slice(List.of(), 0, 10).isEmpty());
    }

    @Test
    void theLabelNamesThePageOnlyWhenThereIsMoreThanOne() {
        assertEquals("7 archives", Paging.label(0, 7, 10));
        assertEquals("Page 2 of 3 · 25 archives", Paging.label(1, 25, 10));
        assertEquals("1 archive", Paging.label(0, 1, 10), "and counts in singular when it should");
    }
}
