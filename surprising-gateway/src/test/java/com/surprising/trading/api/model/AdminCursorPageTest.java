package com.surprising.trading.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminCursorPageTest {

    @Test
    void cursorPreservesInstantPrecisionAndRejectsOldFormat() {
        Instant timestamp = Instant.parse("2026-07-03T12:30:45.123456789Z");
        AdminCursorPage.SortSpec sort = new AdminCursorPage.SortSpec(
                "updatedAt", "updated_at", "id", true);

        AdminCursorPage.CursorPage<Row> page = AdminCursorPage.page(
                List.of(new Row(timestamp, 42L), new Row(timestamp.minusSeconds(1), 41L)),
                1,
                sort,
                Row::updatedAt,
                Row::id);

        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(page.nextCursor());
        assertThat(decoded.timestamp()).isEqualTo(timestamp);
        assertThat(decoded.id()).isEqualTo(42L);

        String oldFormat = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1783081845123:99".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> AdminCursorPage.decodeCursor(oldFormat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid cursor");
    }

    private record Row(Instant updatedAt, long id) {
    }
}
