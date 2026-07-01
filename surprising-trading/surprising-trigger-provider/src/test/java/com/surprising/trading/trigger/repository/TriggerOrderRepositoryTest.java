package com.surprising.trading.trigger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TriggerOrderRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void markPriceTicksUsesPersistedMarkUnitsAndCurrentTickSize() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("price_mark_ticks"), any(RowMapper.class), eq("BTC-USDT"), eq(42L)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("mark_ticks")).thenReturn(65_001L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        OptionalLong markTicks = repository.markPriceTicks("BTC-USDT", 42L);

        assertThat(markTicks).hasValue(65_001L);
        verify(jdbcTemplate).query(contains("i.price_tick_units"), any(RowMapper.class),
                eq("BTC-USDT"), eq(42L));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void claimTriggeredUsesRowLocksAndSymbolPriceCondition() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository repository = new TriggerOrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class),
                eq("BTC-USDT"), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any())).thenReturn(List.of());

        var rows = repository.claimTriggered("BTC-USDT", 70_000L, 9L,
                Instant.parse("2026-07-01T00:00:00Z"), 100, Instant.parse("2026-07-01T00:00:01Z"));

        assertThat(rows).isEmpty();
        verify(jdbcTemplate).query(contains("trigger_condition = 'GREATER_OR_EQUAL'"), any(RowMapper.class),
                eq("BTC-USDT"), any(), eq(70_000L), eq(70_000L), eq(100),
                eq(9L), eq(70_000L), any(), any());
    }
}
