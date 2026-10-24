package io.craftrealm.core.ui;

import java.util.ArrayList;
import java.util.List;

/** Page maths for paged menus. Pure and unit tested. */
public final class Pagination {

    private Pagination() {
    }

    /** Number of pages for {@code total} items. Always at least 1. */
    public static int pages(int total, int perPage) {
        if (perPage <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(total / (double) perPage));
    }

    /** Clamps a page number into the valid range. */
    public static int clamp(int page, int pages) {
        return Math.max(0, Math.min(Math.max(0, pages - 1), page));
    }

    /** The slice shown on a page. */
    public static <T> List<T> slice(List<T> items, int page, int perPage) {
        if (items == null || items.isEmpty() || perPage <= 0) {
            return List.of();
        }
        int from = Math.max(0, page) * perPage;
        if (from >= items.size()) {
            return List.of();
        }
        int to = Math.min(items.size(), from + perPage);
        return new ArrayList<>(items.subList(from, to));
    }

    /** Index of the item at {@code slot} on the current page, or {@code -1}. */
    public static int indexOf(int slot, int firstSlot, int perPage, int page, int total) {
        int offset = slot - firstSlot;
        if (offset < 0 || offset >= perPage) {
            return -1;
        }
        int index = page * perPage + offset;
        return index < total ? index : -1;
    }
}
