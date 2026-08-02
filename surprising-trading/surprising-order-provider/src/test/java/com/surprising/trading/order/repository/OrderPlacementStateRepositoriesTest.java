package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderPlacementStateRepositoriesTest {

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
