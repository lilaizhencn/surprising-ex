package com.surprising.account.api.model;

import com.surprising.account.api.utils.AdminCursorPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminCursorPageTest {

    @Test
    void pageBuildsNextCursorFromLastReturnedRow() {
        AdminCursorPage.SortSpec sort = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "entry_id", true);
        List<Row> rows = List.of(
                new Row(Long.MAX_VALUE, Instant.parse("2026-07-02T01:00:00.123456789Z")),
                new Row(Long.MAX_VALUE - 1, Instant.parse("2026-07-02T01:00:00.123456789Z")));

        AdminCursorPage.CursorPage<Row> page = AdminCursorPage.page(rows, 1, sort, Row::createdAt, Row::id);

        assertThat(page.items()).containsExactly(rows.get(0));
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(page.nextCursor());
        assertThat(decoded.timestamp()).isEqualTo(rows.get(0).createdAt());
        assertThat(decoded.id()).isEqualTo(rows.get(0).id());
    }

    @Test
    void parseSortRejectsUnsupportedFields() {
        AdminCursorPage.SortSpec defaultSort = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "entry_id", true);

        assertThatThrownBy(() -> AdminCursorPage.parseSort("updatedAt.desc", defaultSort, List.of(defaultSort)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported sort");
    }

    private record Row(long id, Instant createdAt) {
    }
}
