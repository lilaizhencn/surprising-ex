package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminCursorPageTest {

    @Test
    void pageBuildsStableNextCursorFromLastReturnedRow() {
        AdminCursorPage.SortSpec sort = new AdminCursorPage.SortSpec(
                "createdAt", "created_at", "event_id", true);
        List<Row> rows = List.of(
                new Row(11L, Instant.parse("2026-07-02T01:00:00.123456789Z")),
                new Row(10L, Instant.parse("2026-07-02T00:59:00Z")));

        AdminCursorPage.CursorPage<Row> page = AdminCursorPage.page(
                rows, 1, sort, Row::createdAt, Row::id);

        assertThat(page.items()).containsExactly(rows.get(0));
        assertThat(page.hasMore()).isTrue();
        assertThat(page.sort()).isEqualTo("createdAt.desc");
        assertThat(page.limit()).isEqualTo(1);
        assertThat(page.nextCursor()).isNotBlank();

        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(page.nextCursor());
        assertThat(decoded.timestamp()).isEqualTo(rows.get(0).createdAt());
        assertThat(decoded.id()).isEqualTo(rows.get(0).id());

        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1783081845123:99".getBytes(StandardCharsets.UTF_8));
        AdminCursorPage.Cursor legacyDecoded = AdminCursorPage.decodeCursor(legacy);
        assertThat(legacyDecoded.timestamp()).isEqualTo(Instant.ofEpochMilli(1_783_081_845_123L));
        assertThat(legacyDecoded.id()).isEqualTo(99L);
    }

    @Test
    void parseSortRejectsUnsupportedSorts() {
        AdminCursorPage.SortSpec defaultSort = new AdminCursorPage.SortSpec(
                "requestedAt", "requested_at", "task_id", true);

        assertThatThrownBy(() -> AdminCursorPage.parseSort("finishedAt.desc", defaultSort, List.of(defaultSort)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported sort");
    }

    @Test
    void seekConditionTracksSortDirection() {
        AdminCursorPage.SortSpec desc = new AdminCursorPage.SortSpec("createdAt", "created_at", "id", true);
        AdminCursorPage.SortSpec asc = new AdminCursorPage.SortSpec("createdAt", "created_at", "id", false);

        assertThat(AdminCursorPage.seekCondition(desc)).contains("created_at < ?").contains("id < ?");
        assertThat(AdminCursorPage.seekCondition(asc)).contains("created_at > ?").contains("id > ?");
    }

    private record Row(long id, Instant createdAt) {
    }
}
