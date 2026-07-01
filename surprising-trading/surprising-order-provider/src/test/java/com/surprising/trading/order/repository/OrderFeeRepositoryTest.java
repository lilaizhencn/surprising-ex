package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderFeeRepositoryTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void resolvesInstrumentDefaultFeeWhenNoUserOverrideExists() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderFeeRepository repository = new OrderFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("WITH instrument_fee"), any(RowMapper.class),
                eq("BTC-USDT"), eq(1L), eq("BTC-USDT"), eq("BTC-USDT"), eq(1001L),
                eq("BTC-USDT"), any(), any())).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row(200L, 500L, "INSTRUMENT"), 0));
                });

        var snapshot = repository.snapshot(1001L, "BTC-USDT", 1L,
                Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().makerFeeRatePpm()).isEqualTo(200L);
        assertThat(snapshot.orElseThrow().takerFeeRatePpm()).isEqualTo(500L);
        assertThat(snapshot.orElseThrow().source()).isEqualTo("INSTRUMENT");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void userSymbolFeeOverrideWinsOverInstrumentDefault() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderFeeRepository repository = new OrderFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("WITH instrument_fee"), any(RowMapper.class),
                eq("BTC-USDT"), eq(1L), eq("BTC-USDT"), eq("BTC-USDT"), eq(1001L),
                eq("BTC-USDT"), any(), any())).thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(row(-100L, 400L, "USER_SYMBOL"), 0));
                });

        var snapshot = repository.snapshot(1001L, "BTC-USDT", 1L,
                Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(snapshot).isPresent();
        assertThat(snapshot.orElseThrow().makerFeeRatePpm()).isEqualTo(-100L);
        assertThat(snapshot.orElseThrow().takerFeeRatePpm()).isEqualTo(400L);
        assertThat(snapshot.orElseThrow().source()).isEqualTo("USER_SYMBOL");
    }

    @Test
    void missingInstrumentReturnsEmptySnapshot() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderFeeRepository repository = new OrderFeeRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("WITH instrument_fee"), any(RowMapper.class),
                eq("BTC-USDT"), eq(1L), eq("BTC-USDT"), eq("BTC-USDT"), eq(1001L),
                eq("BTC-USDT"), any(), any())).thenReturn(List.of());

        assertThat(repository.snapshot(1001L, "BTC-USDT", 1L,
                Instant.parse("2026-07-01T00:00:00Z"))).isEmpty();
    }

    private ResultSet row(long makerFeeRatePpm, long takerFeeRatePpm, String source) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("maker_fee_rate_ppm")).thenReturn(makerFeeRatePpm);
        when(rs.getLong("taker_fee_rate_ppm")).thenReturn(takerFeeRatePpm);
        when(rs.getString("source")).thenReturn(source);
        return rs;
    }
}
