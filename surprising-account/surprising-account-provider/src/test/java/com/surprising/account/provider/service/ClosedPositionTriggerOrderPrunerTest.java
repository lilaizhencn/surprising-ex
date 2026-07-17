package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class ClosedPositionTriggerOrderPrunerTest {

    @Test
    void cancelsAndEnqueuesUserVisibleUpdateInTheClosedPositionScope() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        Instant closedAt = Instant.parse("2026-07-01T00:00:00Z");
        TriggerOrderResponse canceledOrder = canceledOrder(closedAt);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(canceledOrder));
        when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(101L, 201L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":101}");
        ClosedPositionTriggerOrderPruner pruner = new ClosedPositionTriggerOrderPruner(
                jdbcTemplate, properties, objectMapper);

        int canceled = pruner.prune(ProductLine.INVERSE_PERPETUAL, 1001L, "BTC-USD", MarginMode.ISOLATED,
                PositionSide.SHORT, closedAt);

        assertThat(canceled).isEqualTo(1);
        ArgumentCaptor<String> cancelSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> cancelArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(cancelSql.capture(), any(RowMapper.class), cancelArgs.capture());
        assertThat(cancelSql.getValue())
                .contains("status = 'CANCELED'")
                .contains("status = 'PENDING'")
                .contains("product_line = ?")
                .contains("position_side = ?")
                .contains("RETURNING *");
        assertThat(cancelArgs.getValue()).containsExactly(
                ClosedPositionTriggerOrderPruner.REASON,
                java.sql.Timestamp.from(closedAt),
                "INVERSE_PERPETUAL",
                1001L,
                "BTC-USD",
                "ISOLATED",
                "SHORT");
        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(updateSql.capture(), updateArgs.capture());
        assertThat(updateSql.getValue())
                .contains("trading_outbox_events")
                .contains("'TRIGGER_ORDER'");
        assertThat(updateArgs.getValue()).contains(
                201L,
                canceledOrder.triggerOrderId(),
                "surprising.inverse-perp.trigger-order.events.v1",
                "BTC-USD",
                "CANCELED",
                "{\"eventId\":101}");
        ArgumentCaptor<TriggerOrderUpdatedEvent> event = ArgumentCaptor.forClass(TriggerOrderUpdatedEvent.class);
        verify(objectMapper).writeValueAsString(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo(101L);
        assertThat(event.getValue().productLine()).isEqualTo(ProductLine.INVERSE_PERPETUAL);
        assertThat(event.getValue().order()).isEqualTo(canceledOrder);
        assertThat(event.getValue().eventTime()).isEqualTo(closedAt);
    }

    private TriggerOrderResponse canceledOrder(Instant now) {
        return new TriggerOrderResponse(
                501L, 1001L, "client-trigger-1", null, "BTC-USD", OrderSide.BUY,
                TriggerOrderType.STOP_LOSS, TriggerCondition.LESS_OR_EQUAL,
                60_000L, OrderType.MARKET, TimeInForce.GTC, 0L, 10L, MarginMode.ISOLATED,
                PositionSide.SHORT, TriggerOrderStatus.CANCELED, null, null, null,
                ClosedPositionTriggerOrderPruner.REASON, "trace-close", null, null, now.minusSeconds(60), now);
    }
}
