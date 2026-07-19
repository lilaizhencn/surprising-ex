package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

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
                .contains("ON CONFLICT (product_line, user_id, client_order_id) WHERE client_order_id IS NOT NULL DO NOTHING")
                .contains("created_at, updated_at, revision")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    void cancelRequestAtomicallyAdvancesTheOrderRevision() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);

        assertThat(repository.requestCancel(9001L, Instant.parse("2026-07-18T00:00:00Z"))).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("revision = revision + 1");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void findByClientOrderIdScopesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.findByClientOrderId(ProductLine.OPTION, 1001L, "cli-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue()).contains("WHERE product_line = ? AND user_id = ? AND client_order_id = ?");
        assertThat(args.getValue()).containsExactly("OPTION", 1001L, "cli-1");
    }

    @Test
    void findByOrderIdsDeduplicatesAndLoadsOneDatabaseBatch() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);

        assertThat(repository.findByOrderIds(List.of(9001L, 9002L, 9001L))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), args.capture());
        assertThat(sql.getValue())
                .contains("order_id IN (?, ?)")
                .doesNotContain("ORDER BY");
        assertThat(args.getValue()).containsExactly(9001L, 9002L);
    }

    @Test
    void completeReservationsUsesOneConditionalBatchUpdate() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        Instant completedAt = Instant.parse("2026-07-19T00:00:10Z");

        repository.completeReservations(List.of(
                new OrderRepository.ReservationCompletion(9001L, true, null, completedAt),
                new OrderRepository.ReservationCompletion(9002L, false, "insufficient", completedAt)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), args.capture());
        assertThat(sql.getValue())
                .contains("WITH input(order_id, accepted, reject_reason, completed_at)")
                .contains("locked AS MATERIALIZED")
                .contains("ORDER BY o.order_id")
                .contains("FOR UPDATE OF o")
                .contains("UPDATE trading_orders o")
                .contains("o.status = 'PENDING_RESERVE'")
                .contains("RETURNING o.*");
        assertThat(args.getValue()).containsExactly(
                9001L, true, null, Timestamp.from(completedAt),
                9002L, false, "insufficient", Timestamp.from(completedAt));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void reduceOnlyMaintenanceLocksOnlyTheOwnedProductUserAndPositionSide() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.lockOpenReduceOnlyOrders(
                ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", PositionSide.LONG,
                java.time.Instant.parse("2026-07-19T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("reduce_only = TRUE")
                .contains("CANCEL_REQUESTED")
                .contains("created_at <= ?")
                .contains("ORDER BY created_at ASC, order_id ASC")
                .contains("FOR UPDATE");
        assertThat(args.getValue()).containsExactly("LINEAR_PERPETUAL", 1001L, "BTC-USDT", "LONG",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-07-19T00:00:00Z")));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockUserSymbolMarginScopeUsesProductScopedAdvisoryLockKey() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);

        repository.lockUserSymbolMarginScope(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT");

        verify(jdbcTemplate).query(contains("pg_advisory_xact_lock"),
                any(ResultSetExtractor.class), eq("LINEAR_DELIVERY:1001:BTC-USDT"));
    }

    @Test
    void activeMarginModeConflictChecksPositionsOrdersAndTriggers() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("account_positions"), eq(Boolean.class),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED")))
                .thenReturn(true);

        boolean conflict = repository.hasActiveMarginModeConflict(ProductLine.LINEAR_DELIVERY, 1001L,
                "BTC-USDT", MarginMode.ISOLATED);

        assertThat(conflict).isTrue();
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("p.product_line = ?")
                                && sql.contains("o.product_line = ?")
                                && sql.contains("t.product_line = ?")
                                && sql.contains("a.product_line = ?")),
                eq(Boolean.class),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT"), eq("ISOLATED"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void lockedPositionScopesByProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.lockedPosition(ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C",
                MarginMode.CROSS, com.surprising.trading.api.model.PositionSide.NET);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("FOR UPDATE");
        assertThat(args.getValue()).containsExactly("OPTION", 1001L, "BTC-USDT-260925-70000-C",
                "CROSS", "NET");
    }

    @Test
    void orderMatchesContractTypeUsesOrderProductLine() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);

        boolean matched = repository.orderMatchesContractType(9001L, "VANILLA_OPTION");

        assertThat(matched).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Boolean.class), args.capture());
        assertThat(sql.getValue())
                .contains("FROM trading_orders")
                .contains("product_line = ?")
                .doesNotContain("JOIN instruments");
        assertThat(args.getValue()).containsExactly(9001L, "OPTION");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void openOrdersFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.openOrders(1001L, "BTC-USDT", 50, "LINEAR_DELIVERY");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("FROM instruments i");
        assertThat(args.getValue()).containsExactly(1001L, "BTC-USDT", "BTC-USDT",
                "LINEAR_DELIVERY", "LINEAR_DELIVERY", 50);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adminCancelableOrdersFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.adminCancelableOrders(1001L, "BTC-USDT", "COIN_PERPETUAL", 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), args.capture());
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("FROM instruments i")
                .contains("remaining_quantity_steps > 0");
        assertThat(args.getValue()).containsExactly(1001L, 1001L, "BTC-USDT", "BTC-USDT",
                "INVERSE_PERPETUAL", "INVERSE_PERPETUAL", 25);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void adminOrderPageFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.adminOrderPage(1001L, "BTC-USDT", OrderStatus.ACCEPTED, 9001L, 25,
                "LINEAR_DELIVERY", null, "createdAt.asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("FROM instruments i");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void matchTradePageFiltersByProductLineWhenContractTypeProvided() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());

        repository.matchTradePage(1001L, 9001L, "BTC-USDT", 25,
                "VANILLA_OPTION", null, "eventTime.desc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .doesNotContain("JOIN instruments i");
    }
}
