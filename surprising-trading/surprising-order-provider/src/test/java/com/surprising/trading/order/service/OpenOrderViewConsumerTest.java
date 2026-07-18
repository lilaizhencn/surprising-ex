package com.surprising.trading.order.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenOrderViewConsumerTest {

    @Test
    void ignoresOrdersFromOtherProductLinesOnTheSharedLifecycleTopic() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        OrderRepository repository = mock(OrderRepository.class);
        RedisOpenOrderView view = mock(RedisOpenOrderView.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OpenOrderViewConsumer consumer = new OpenOrderViewConsumer(mapper, repository, view, properties);
        OrderEvent event = new OrderEvent(1L, 9001L, 1001L, "BTC-USDT", OrderEventType.ACCEPTED,
                OrderStatus.ACCEPTED, null, Instant.parse("2026-07-18T00:00:00Z"));
        when(mapper.readValue(anyString(), eq(OrderEvent.class))).thenReturn(event);
        when(repository.findByOrderId(9001L)).thenReturn(Optional.of(mock(OrderRecord.class)));
        when(repository.findByOrderId(9001L).get().productLine()).thenReturn(ProductLine.INVERSE_PERPETUAL);

        consumer.onOrder(new ConsumerRecord<>(consumer.orderEventsTopic(), 0, 0L, "BTC-USDT", "{}"));

        verifyNoInteractions(view);
    }
}
