package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderRepositoryTest {

    @Test
    void orderInsertOnlyIgnoresClientOrderIdConflict() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(0);

        boolean inserted = repository.insert(new OrderRecord(
                9001L,
                1001L,
                "cli-1",
                "BTC-USDT",
                7L,
                OrderSide.BUY,
                OrderType.LIMIT,
                TimeInForce.GTC,
                65_000L,
                10L,
                0L,
                10L,
                200L,
                500L,
                false,
                false,
                OrderStatus.ACCEPTED,
                null,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z")));

        assertThat(inserted).isFalse();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (user_id, client_order_id) WHERE client_order_id IS NOT NULL DO NOTHING")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockUserSymbolMarginScopeUsesSharedAdvisoryLockKey() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);

        repository.lockUserSymbolMarginScope(1001L, "BTC-USDT");

        verify(jdbcTemplate).query(contains("pg_advisory_xact_lock"),
                any(ResultSetExtractor.class), eq("1001:BTC-USDT"));
    }

    @Test
    void activeMarginModeConflictChecksPositionsOrdersAndTriggers() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("account_positions"), eq(Boolean.class),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED")))
                .thenReturn(true);

        boolean conflict = repository.hasActiveMarginModeConflict(1001L, "BTC-USDT", MarginMode.ISOLATED);

        assertThat(conflict).isTrue();
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("trading_trigger_orders") && sql.contains("trading_algo_orders")),
                eq(Boolean.class),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq(1001L), eq("BTC-USDT"), eq("ISOLATED"));
    }
}
