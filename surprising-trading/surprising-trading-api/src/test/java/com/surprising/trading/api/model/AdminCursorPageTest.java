package com.surprising.trading.api.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminCursorPageTest {

    @Test
    void cursorPreservesInstantPrecisionAndStillDecodesLegacyEpochMillisCursor() {
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

        String legacy = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("1783081845123:99".getBytes(StandardCharsets.UTF_8));
        AdminCursorPage.Cursor legacyDecoded = AdminCursorPage.decodeCursor(legacy);
        assertThat(legacyDecoded.timestamp()).isEqualTo(Instant.ofEpochMilli(1_783_081_845_123L));
        assertThat(legacyDecoded.id()).isEqualTo(99L);
    }

    private record Row(Instant updatedAt, long id) {
    }
}
