package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrderPlacementStateRepositoriesTest {

    @Test
    void algorithmOrderConflictOnlyQueriesAlgorithmOrderTable() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderAlgoStateRepository repository = new OrderAlgoStateRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("trading_algo_orders"), eq(Boolean.class),
                eq("OPTION"), eq(1001L), eq("BTC-USDT-260925-70000-C"), eq("CROSS")))
                .thenReturn(true);

        boolean conflict = repository.hasMarginModeConflict(
                ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C", MarginMode.CROSS);

        assertThat(conflict).isTrue();
        verify(jdbcTemplate).queryForObject(contains("FROM trading_algo_orders"), eq(Boolean.class),
                eq("OPTION"), eq(1001L), eq("BTC-USDT-260925-70000-C"), eq("CROSS"));
    }

    @Test
    void triggerOrderConflictOnlyQueriesTriggerOrderTable() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderTriggerStateRepository repository = new OrderTriggerStateRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(contains("trading_trigger_orders"), eq(Boolean.class),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("ISOLATED")))
                .thenReturn(true);

        boolean conflict = repository.hasMarginModeConflict(
                ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT-260925", MarginMode.ISOLATED);

        assertThat(conflict).isTrue();
        verify(jdbcTemplate).queryForObject(contains("FROM trading_trigger_orders"), eq(Boolean.class),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("ISOLATED"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void positionModeOnlyQueriesPositionModeTable() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderPositionModeRepository repository = new OrderPositionModeRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("account_position_modes"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("HEDGE"));

        PositionMode mode = repository.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L);

        assertThat(mode).isEqualTo(PositionMode.HEDGE);
        verify(jdbcTemplate).query(contains("FROM account_position_modes"),
                any(RowMapper.class), eq(new Object[]{"LINEAR_PERPETUAL", 1001L}));
    }

    @Test
    void orderEventOnlyWritesOrderEventTable() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        OrderEventRepository repository = new OrderEventRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        Instant eventTime = Instant.parse("2026-07-31T00:00:00Z");
        OrderEvent event = new OrderEvent(9100L, 9001L, 1001L, "BTC-USDT",
                OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, null, eventTime, "trace-order");

        repository.insert(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO trading_order_events")
                .doesNotContain("trading_orders");
    }
}
