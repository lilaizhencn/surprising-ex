package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderRepositoryTest {

    @Test
    void projectionRejectsRowsFromAnotherUserPartition() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OrderRepository repository = new OrderRepository(jdbcTemplate);
        OrderRecord order = order(ProductLine.LINEAR_PERPETUAL, 2002L);

        assertThatThrownBy(() -> repository.replaceProjection(ProductLine.LINEAR_PERPETUAL, 1001L, List.of(order)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分区元数据");
    }

    @Test
    void projectionDeletesOnlyTheOwnedUserPartition() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        org.mockito.Mockito.when(jdbcTemplate.update(any(String.class), eq("LINEAR_PERPETUAL"), eq(1001L)))
                .thenReturn(1);
        OrderRepository repository = new OrderRepository(jdbcTemplate);

        repository.replaceProjection(ProductLine.LINEAR_PERPETUAL, 1001L, List.of());

        verify(jdbcTemplate).update(any(String.class), eq("LINEAR_PERPETUAL"), eq(1001L));
    }

    private OrderRecord order(ProductLine line, long userId) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new OrderRecord(9001L, line, userId, "client-1", "BTC-USDT", 1L, OrderSide.BUY,
                OrderType.LIMIT, TimeInForce.GTC, 100L, 10L, 0L, 10L, MarginMode.CROSS, PositionSide.NET,
                100L, 200L, false, false, "USDT_PERPETUAL", "USDT", 1000L, OrderStatus.ACCEPTED, null,
                now, now, 1L);
    }
}
