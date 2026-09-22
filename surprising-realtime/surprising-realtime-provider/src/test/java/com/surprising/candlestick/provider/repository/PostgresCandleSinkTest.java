package com.surprising.candlestick.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.provider.aggregation.CandleSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresCandleSinkTest {
    @Test
    void persistsOnlyClosedOneMinuteCandles() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        PostgresCandleSink sink = new PostgresCandleSink(jdbc);

        sink.upsertBatch(List.of(snapshot("5m", CandleStatus.CLOSED), snapshot("1m", CandleStatus.PARTIAL)));
        verify(jdbc, never()).batchUpdate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter.class));

        sink.upsertBatch(List.of(snapshot("1m", CandleStatus.CLOSED)));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).batchUpdate(sql.capture(),
                org.mockito.ArgumentMatchers.any(BatchPreparedStatementSetter.class));
        assertThat(sql.getValue()).contains("ON CONFLICT (symbol, period, open_time) DO NOTHING");
    }

    private CandleSnapshot snapshot(String period, CandleStatus status) {
        Instant open = Instant.parse("2026-08-25T10:00:00Z");
        return new CandleSnapshot("BTC-USDT", period, open, open.plusSeconds(60),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1, "a", "a", 1L, 1L,
                status, open, 0, 1L);
    }
}
