package com.surprising.trading.api.utils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void cursorPreservesInstantPrecisionAndRejectsOldFormat() {
        Instant timestamp = Instant.parse("2026-07-03T12:30:45.123456789Z");
        AdminCursorPage.SortSpec sort = new AdminCursorPage.SortSpec(
                "updatedAt", "updated_at", "id", true);
        AdminCursorPage.CursorPage<Row> page = AdminCursorPage.page(
                List.of(new Row(42L, timestamp), new Row(41L, timestamp.minusSeconds(1))),
                1,
                sort,
                Row::createdAt,
                Row::id);

        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(page.nextCursor());
        assertThat(decoded.timestamp()).isEqualTo(timestamp);
        assertThat(decoded.id()).isEqualTo(42L);

        String oldFormat = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1783081845123:99".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> AdminCursorPage.decodeCursor(oldFormat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
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
