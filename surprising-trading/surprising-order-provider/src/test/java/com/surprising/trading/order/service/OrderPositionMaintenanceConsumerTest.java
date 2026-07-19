package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OrderPositionMaintenanceConsumerTest {

    @Test
    void validatesUserPartitionKeyAndDelegatesDurablePositionSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OrderService orderService = mock(OrderService.class);
        OrderPositionMaintenanceConsumer consumer =
                new OrderPositionMaintenanceConsumer(objectMapper, properties, orderService);
        PositionUpdatedEvent event = event();

        consumer.onPositionUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, event.partitionKey(), objectMapper.writeValueAsString(event))));

        verify(orderService).onPositionUpdated(event);
    }

    @Test
    void rejectsSymbolKeySoMaintenanceCannotLosePerUserOrdering() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OrderService orderService = mock(OrderService.class);
        OrderPositionMaintenanceConsumer consumer =
                new OrderPositionMaintenanceConsumer(objectMapper, properties, orderService);
        PositionUpdatedEvent event = event();

        assertThatThrownBy(() -> consumer.onPositionUpdated(List.of(new ConsumerRecord<>(
                consumer.topic(), 0, 1L, event.symbol(), objectMapper.writeValueAsString(event)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to maintain reduce-only orders");
        verifyNoInteractions(orderService);
    }

    private PositionUpdatedEvent event() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return new PositionUpdatedEvent(
                9001L, 8001L, 1001L, "BTC-USDT", 7L, MarginMode.CROSS, PositionSide.NET,
                2L, 65_000L, 0L, now, "trace-position-prune");
    }
}
