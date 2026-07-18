package com.surprising.trading.order.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenOrderViewConsumerTest {

    @Test
    void projectsTakerAndMakerOrdersFromDurableMatchResult() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        OrderRepository repository = mock(OrderRepository.class);
        RedisOpenOrderView view = mock(RedisOpenOrderView.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OpenOrderViewConsumer consumer = new OpenOrderViewConsumer(mapper, repository, view, properties);
        Instant eventTime = Instant.parse("2026-07-18T00:00:00Z");
        MatchTradeEvent trade = new MatchTradeEvent(7001L, 8001L, "BTC-USDT",
                9001L, 1L, 1001L, OrderSide.BUY,
                9002L, 1L, 1002L, 500L, 200L,
                6_000_000L, 3L, false, true, eventTime);
        MatchResultEvent result = new MatchResultEvent(8001L, 9001L, 1001L, "BTC-USDT", 1L,
                OrderCommandType.PLACE, "SUCCESS", 3L, OrderStatus.PARTIALLY_FILLED,
                eventTime, List.of(trade));
        OrderRecord taker = mock(OrderRecord.class);
        OrderRecord maker = mock(OrderRecord.class);
        when(mapper.readValue(anyString(), eq(MatchResultEvent.class))).thenReturn(result);
        when(repository.findByOrderIds(Set.of(9001L, 9002L))).thenReturn(Map.of(9001L, taker, 9002L, maker));
        when(taker.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        when(maker.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);

        consumer.onEvents(List.of(
                new ConsumerRecord<>(consumer.matchResultsTopic(), 0, 0L, "BTC-USDT", "{}")));

        verify(repository).findByOrderIds(Set.of(9001L, 9002L));
        verify(view).synchronize(taker);
        verify(view).synchronize(maker);
    }

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
        OrderRecord order = mock(OrderRecord.class);
        when(order.productLine()).thenReturn(ProductLine.INVERSE_PERPETUAL);
        when(repository.findByOrderIds(Set.of(9001L))).thenReturn(Map.of(9001L, order));

        consumer.onEvents(List.of(
                new ConsumerRecord<>(consumer.orderEventsTopic(), 0, 0L, "BTC-USDT", "{}")));

        verifyNoInteractions(view);
    }

    @Test
    void loadsRepeatedOrdersOncePerKafkaBatch() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        OrderRepository repository = mock(OrderRepository.class);
        RedisOpenOrderView view = mock(RedisOpenOrderView.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OpenOrderViewConsumer consumer = new OpenOrderViewConsumer(mapper, repository, view, properties);
        Instant eventTime = Instant.parse("2026-07-18T00:00:00Z");
        OrderEvent first = new OrderEvent(1L, 9001L, 1001L, "BTC-USDT", OrderEventType.ACCEPTED,
                OrderStatus.ACCEPTED, null, eventTime);
        OrderEvent second = new OrderEvent(2L, 9001L, 1001L, "BTC-USDT", OrderEventType.CANCEL_REQUESTED,
                OrderStatus.CANCELED, null, eventTime);
        OrderRecord order = mock(OrderRecord.class);
        when(order.productLine()).thenReturn(ProductLine.LINEAR_PERPETUAL);
        when(mapper.readValue("first", OrderEvent.class)).thenReturn(first);
        when(mapper.readValue("second", OrderEvent.class)).thenReturn(second);
        when(repository.findByOrderIds(Set.of(9001L))).thenReturn(Map.of(9001L, order));

        consumer.onEvents(List.of(
                new ConsumerRecord<>(consumer.orderEventsTopic(), 0, 0L, "BTC-USDT", "first"),
                new ConsumerRecord<>(consumer.orderEventsTopic(), 0, 1L, "BTC-USDT", "second")));

        verify(repository).findByOrderIds(Set.of(9001L));
        verify(view).synchronize(order);
    }
}
