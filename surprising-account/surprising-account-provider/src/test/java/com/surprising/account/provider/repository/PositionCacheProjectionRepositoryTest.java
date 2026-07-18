package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PositionCacheProjectionRepositoryTest {

    @Test
    void capturesOneFinalSnapshotWithoutWritingOutbox() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PositionCacheEvent expected = event();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("NET")))
                .thenReturn(java.util.List.of(expected));
        PositionCacheProjectionRepository repository = new PositionCacheProjectionRepository(jdbcTemplate);

        PositionCacheEvent actual = repository.captureFinalSnapshot(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET);

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(), any(RowMapper.class), eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"),
                eq("CROSS"), eq("NET"));
        assertThat(sql.getValue())
                .contains("GREATEST(p.cache_revision")
                .contains("FROM account_positions")
                .doesNotContain("account_outbox_events");
    }

    private PositionCacheEvent event() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionCacheEvent(
                9L, ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 7L,
                MarginMode.CROSS, PositionSide.NET, 3L, 60_000L, 180_000L, 100L,
                "USDT", 20_000L, now, now, 9L);
    }
}
