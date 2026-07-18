package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.PositionState;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReduceOnlyOrderPrunerTest {

    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void productTopicsScopeOpenReduceOnlyOrdersByProductLine() {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT-240927"), eq("NET"), eq("LINEAR_DELIVERY")))
                .thenReturn(List.of());

        new ReduceOnlyOrderPruner(jdbcTemplate, properties, new ObjectMapper())
                .prune(1001L, "BTC-USDT-240927", new PositionState(4L, 1L, 100L, 0L), NOW);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq(1001L), eq("BTC-USDT-240927"), eq("NET"), eq("LINEAR_DELIVERY"));
        assertThat(sql.getValue())
                .doesNotContain("JOIN instruments i")
                .contains("o.product_line = ?");
    }

    @Test
    void cancelsNewestReduceOnlyOrdersBeyondCurrentPositionCapacity() throws Exception {
        whenOpenOrders(
                order(101L, "SELL", 1L, 3L, "ACCEPTED", Instant.parse("2026-07-01T00:00:00Z")),
                order(102L, "SELL", 1L, 3L, "ACCEPTED", Instant.parse("2026-07-01T00:00:01Z")));
        when(jdbcTemplate.update(contains("UPDATE trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("nextval"), eq(Long.class), any()))
                .thenReturn(501L, 601L, 701L, 602L);

        pruner().prune(1001L, "BTC-USDT", new PositionState(4L, 1L, 100L, 0L), NOW);

        verify(jdbcTemplate).update(contains("UPDATE trading_orders"),
                eq("REDUCE_ONLY_POSITION_REDUCED"), any(Timestamp.class), eq(102L));
        verify(jdbcTemplate, never()).update(contains("UPDATE trading_orders"),
                eq("REDUCE_ONLY_POSITION_REDUCED"), any(Timestamp.class), eq(101L));

        ArgumentCaptor<String> orderUpdate = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(orderUpdate.capture(), eq("REDUCE_ONLY_POSITION_REDUCED"),
                any(Timestamp.class), eq(102L));
        assertThat(orderUpdate.getValue()).contains("revision = revision + 1");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(contains("INSERT INTO trading_outbox_events"),
                eq(602L), eq("ORDER"), eq(102L), eq("surprising.perp.order.commands.v1"),
                eq("BTC-USDT"), eq("CANCEL"), payload.capture(), any(Timestamp.class), any(Timestamp.class),
                any(Timestamp.class));
        assertThat(payload.getValue()).contains("\"commandType\":\"CANCEL\"").contains("\"orderId\":102");
    }

    @Test
    void cancelsReduceOnlyOrdersWhenPositionIsFlatOrVersionChanged() throws Exception {
        whenOpenOrders(
                order(201L, "SELL", 1L, 2L, "ACCEPTED", Instant.parse("2026-07-01T00:00:00Z")),
                order(202L, "BUY", 2L, 2L, "ACCEPTED", Instant.parse("2026-07-01T00:00:01Z")));
        when(jdbcTemplate.update(contains("UPDATE trading_orders"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_order_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_outbox_events"), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("nextval"), eq(Long.class), any()))
                .thenReturn(501L, 601L, 701L, 602L, 502L, 603L, 702L, 604L);

        pruner().prune(1001L, "BTC-USDT", new PositionState(0L, 0L, 0L, 0L), NOW);

        verify(jdbcTemplate).update(contains("UPDATE trading_orders"),
                eq("REDUCE_ONLY_POSITION_REDUCED"), any(Timestamp.class), eq(201L));
        verify(jdbcTemplate).update(contains("UPDATE trading_orders"),
                eq("REDUCE_ONLY_POSITION_REDUCED"), any(Timestamp.class), eq(202L));
    }

    @Test
    void failsBeforePruningWhenPositionQuantityCannotBeAbsed() throws Exception {
        whenOpenOrders(order(301L, "BUY", 1L, 2L, "ACCEPTED", Instant.parse("2026-07-01T00:00:00Z")));

        assertThatThrownBy(() -> pruner().prune(1001L, "BTC-USDT",
                new PositionState(Long.MIN_VALUE, 1L, 100L, 0L), NOW))
                .isInstanceOf(ArithmeticException.class);

        verify(jdbcTemplate, never()).update(contains("UPDATE trading_orders"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_order_events"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO trading_outbox_events"), any(Object[].class));
    }

    private ReduceOnlyOrderPruner pruner() {
        return new ReduceOnlyOrderPruner(jdbcTemplate, new AccountProperties(), new ObjectMapper());
    }

    @SafeVarargs
    private void whenOpenOrders(RowConfigurer... orders) {
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.util.ArrayList<Object> rows = new java.util.ArrayList<>();
                    for (int i = 0; i < orders.length; i++) {
                        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                        orders[i].configure(rs);
                        rows.add(mapper.mapRow(rs, i));
                    }
                    return rows;
                });
    }

    private RowConfigurer order(long orderId,
                                String side,
                                long instrumentVersion,
                                long remainingSteps,
                                String status,
                                Instant createdAt) {
        return rs -> {
            when(rs.getLong("order_id")).thenReturn(orderId);
            when(rs.getLong("user_id")).thenReturn(1001L);
            when(rs.getString("client_order_id")).thenReturn("ro-" + orderId);
            when(rs.getString("symbol")).thenReturn("BTC-USDT");
            when(rs.getLong("instrument_version")).thenReturn(instrumentVersion);
            when(rs.getString("side")).thenReturn(side);
            when(rs.getString("order_type")).thenReturn("LIMIT");
            when(rs.getString("time_in_force")).thenReturn("GTC");
            when(rs.getLong("price_ticks")).thenReturn(100L);
            when(rs.getLong("quantity_steps")).thenReturn(remainingSteps);
            when(rs.getLong("remaining_quantity_steps")).thenReturn(remainingSteps);
            when(rs.getString("status")).thenReturn(status);
            when(rs.getBoolean("post_only")).thenReturn(false);
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
        };
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    @FunctionalInterface
    private interface RowConfigurer {
        void configure(ResultSet rs) throws Exception;
    }
}
