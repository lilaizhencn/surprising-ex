package com.surprising.candlestick.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CandleQueryRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void higherPeriodRangeUsesBoundedClosedM1SqlAggregation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CandleQueryRepository repository = new CandleQueryRepository(jdbc);

        repository.findRange("BTC-USDT", "5m", Instant.parse("2026-08-25T10:02:00Z"),
                Instant.parse("2026-08-25T10:57:00Z"), 100);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), arguments.capture());
        assertThat(sql.getValue()).contains("period = '1m'", "status = 'CLOSED'", "open_time >= ?",
                "open_time < ?", "sum(base_volume)", "sum(quote_volume)", "sum(trade_count)",
                "ORDER BY open_time ASC", "ORDER BY open_time DESC");
        assertThat(sql.getValue()).contains("AND open_time >= ? AND open_time < ? ORDER BY open_time ASC");
        assertThat(arguments.getValue()).containsExactly(300L, 300L, "BTC-USDT",
                java.sql.Timestamp.from(Instant.parse("2026-08-25T10:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-08-25T11:00:00Z")), "5m", 300L,
                java.sql.Timestamp.from(Instant.parse("2026-08-25T10:02:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-08-25T10:57:00Z")), 100);
    }
}
