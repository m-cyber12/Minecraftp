package io.craftrealm.core.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void pagesAlwaysAtLeastOne() {
        assertEquals(1, Pagination.pages(0, 10));
        assertEquals(1, Pagination.pages(10, 10));
        assertEquals(2, Pagination.pages(11, 10));
    }

    @Test
    void clampsOutOfRangePages() {
        assertEquals(0, Pagination.clamp(-3, 4));
        assertEquals(3, Pagination.clamp(99, 4));
    }

    @Test
    void slicesTheRequestedPage() {
        List<Integer> items = IntStream.range(0, 25).boxed().toList();
        assertEquals(10, Pagination.slice(items, 0, 10).size());
        assertEquals(5, Pagination.slice(items, 2, 10).size());
        assertTrue(Pagination.slice(items, 5, 10).isEmpty());
    }

    @Test
    void mapsSlotToIndex() {
        assertEquals(12, Pagination.indexOf(11, 9, 5, 2, 100));
        assertEquals(-1, Pagination.indexOf(8, 9, 5, 2, 100));
        assertEquals(-1, Pagination.indexOf(9, 9, 5, 2, 10));
    }
}
