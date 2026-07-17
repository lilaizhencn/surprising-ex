package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderBookDepthUpdateType;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.websocket.api.model.ExecutionReportEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KafkaFanoutConsumerTest {

    @Mock
    private SubscriptionRegistry registry;

    @Mock
    private CandleUpdateCoalescer candleUpdateCoalescer;

    @Test
    void fansOutOrderBookDepthBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderBookDepthEvent event = new OrderBookDepthEvent("BTC-USDT-SPOT", 7L, 6L,
                OrderBookDepthUpdateType.DELTA, 50,
                List.of(new OrderBookLevel(99L, 5L, 1L)),
                List.of(new OrderBookLevel(101L, 8L, 2L)), eventTime);

        consumer.onOrderBookDepth(new ConsumerRecord<>("surprising.perp.orderbook.depth.v1", 0, 0L,
                "BTC-USDT-SPOT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.ORDER_BOOK_DEPTH);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT-SPOT");
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void productTopicFanoutPublishesCurrentProductLine() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer,
                properties);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderBookDepthEvent event = new OrderBookDepthEvent("BTC-USDT-260925", 7L, 6L,
                OrderBookDepthUpdateType.DELTA, 50,
                List.of(new OrderBookLevel(99L, 5L, 1L)),
                List.of(new OrderBookLevel(101L, 8L, 2L)), eventTime);

        consumer.onOrderBookDepth(new ConsumerRecord<>("surprising.linear-delivery.orderbook.depth.v1", 0, 0L,
                "BTC-USDT-260925", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        verify(registry).publish(topic.capture(), org.mockito.ArgumentMatchers.eq(event), eq(eventTime));
        assertThat(topic.getValue().productLine()).isEqualTo(ProductLine.LINEAR_DELIVERY);
    }

    @Test
    void productTopicFanoutRejectsOtherProductMarketDataTopicBeforePublishing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer,
                properties);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderBookDepthEvent event = new OrderBookDepthEvent("BTC-USDT-260925", 7L, 6L,
                OrderBookDepthUpdateType.DELTA, 50,
                List.of(new OrderBookLevel(99L, 5L, 1L)),
                List.of(new OrderBookLevel(101L, 8L, 2L)), eventTime);

        assertThatThrownBy(() -> consumer.onOrderBookDepth(new ConsumerRecord<>(
                "surprising.inverse-delivery.orderbook.depth.v1", 0, 0L,
                "BTC-USDT-260925", objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fanout order book depth")
                .hasRootCauseMessage("order book depth topic must match current product line: expected="
                        + "surprising.linear-delivery.orderbook.depth.v1 actual="
                        + "surprising.inverse-delivery.orderbook.depth.v1");

        verifyNoInteractions(registry);
    }

    @Test
    void productTopicFanoutRejectsOtherProductPrivateOrderTopicBeforePublishing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer,
                properties);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderEvent event = new OrderEvent(1L, 11L, 1001L, "BTC-USDT-260925-70000-C",
                OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, null, eventTime, "trace-order-topic");

        assertThatThrownBy(() -> consumer.onOrderEvent(new ConsumerRecord<>(
                "surprising.linear-delivery.order.events.v1", 0, 0L,
                "BTC-USDT-260925-70000-C", objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fanout order event")
                .hasRootCauseMessage("order event topic must match current product line: expected="
                        + "surprising.option.order.events.v1 actual=surprising.linear-delivery.order.events.v1");

        verifyNoInteractions(registry);
    }

    @Test
    void fansOutFundingRateDecimalEventBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        PerpFundingRateEvent event = new PerpFundingRateEvent("BTC-USDT", new BigDecimal("0.000100"),
                eventTime.plusSeconds(3600), 8, 9L, eventTime);

        consumer.onFundingRate(new ConsumerRecord<>("surprising.perp.funding.rate.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.FUNDING_RATE);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutFreshMarkPriceBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        MarkPriceEvent event = markPriceEvent(Instant.now());

        consumer.onMarkPrice(new ConsumerRecord<>("surprising.perp.mark.price.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        verify(registry).publish(eq(new SubscriptionTopic(WsChannel.MARK_PRICE, "BTC-USDT", null, null)),
                eq(event), eq(event.eventTime()));
    }

    @Test
    void dropsStaleMarkPriceWithoutFanoutOrRetryFailure() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        MarkPriceEvent event = markPriceEvent(Instant.now().minusSeconds(4));

        consumer.onMarkPrice(new ConsumerRecord<>("surprising.perp.mark.price.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        verifyNoInteractions(registry);
    }

    @Test
    void fansOutMatchTradeToPublicTradesAndPrivateMatches() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        MatchTradeEvent event = new MatchTradeEvent(91L, 11L, "BTC-USDT", 202L, 7L,
                2002L, OrderSide.BUY, MarginMode.CROSS, 101L, 5L, 1001L, MarginMode.CROSS,
                5L, 2L, 600_000L, 3L, true, false, eventTime, "trace-trade-1");

        consumer.onMatchTrade(new ConsumerRecord<>("surprising.perp.match.trades.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry, org.mockito.Mockito.times(5)).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getAllValues().get(0).channel()).isEqualTo(WsChannel.TRADES);
        assertThat(topic.getAllValues().get(0).userId()).isNull();
        assertThat(topic.getAllValues().get(1).channel()).isEqualTo(WsChannel.MATCHES);
        assertThat(topic.getAllValues().get(1).userId()).isEqualTo(2002L);
        assertThat(topic.getAllValues().get(2).channel()).isEqualTo(WsChannel.MATCHES);
        assertThat(topic.getAllValues().get(2).userId()).isEqualTo(1001L);
        assertThat(topic.getAllValues().get(3).channel()).isEqualTo(WsChannel.EXECUTION_REPORTS);
        assertThat(topic.getAllValues().get(3).userId()).isEqualTo(2002L);
        assertThat(topic.getAllValues().get(4).channel()).isEqualTo(WsChannel.EXECUTION_REPORTS);
        assertThat(topic.getAllValues().get(4).userId()).isEqualTo(1001L);
        assertThat(payload.getAllValues().subList(0, 3)).containsOnly(event);
        ExecutionReportEvent takerReport = (ExecutionReportEvent) payload.getAllValues().get(3);
        ExecutionReportEvent makerReport = (ExecutionReportEvent) payload.getAllValues().get(4);
        assertThat(takerReport.reportType()).isEqualTo("TRADE");
        assertThat(takerReport.liquidityRole()).isEqualTo("TAKER");
        assertThat(takerReport.side()).isEqualTo("BUY");
        assertThat(takerReport.orderId()).isEqualTo(202L);
        assertThat(takerReport.counterpartyOrderId()).isEqualTo(101L);
        assertThat(takerReport.orderCompleted()).isTrue();
        assertThat(makerReport.reportType()).isEqualTo("TRADE");
        assertThat(makerReport.liquidityRole()).isEqualTo("MAKER");
        assertThat(makerReport.side()).isEqualTo("SELL");
        assertThat(makerReport.orderId()).isEqualTo(101L);
        assertThat(makerReport.counterpartyOrderId()).isEqualTo(202L);
        assertThat(makerReport.orderCompleted()).isFalse();
        assertThat(makerReport.priceTicks()).isEqualTo(600_000L);
        assertThat(makerReport.quantitySteps()).isEqualTo(3L);
    }

    private MarkPriceEvent markPriceEvent(Instant eventTime) {
        BigDecimal price = new BigDecimal("50000");
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 1L, 5_000_000L, 50_000L,
                price, price, price, price, price, new BigDecimal("49990"), new BigDecimal("50010"),
                BigDecimal.ZERO, eventTime.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                new BigDecimal("49000"), new BigDecimal("51000"), 1L, PriceStatus.HEALTHY,
                eventTime, eventTime);
    }

    @Test
    void fansOutWildcardPrivateOrderEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        OrderEvent event = new OrderEvent(1L, 11L, 1001L, SubscriptionTopic.WILDCARD,
                OrderEventType.CANCEL_REQUESTED, OrderStatus.CANCEL_REQUESTED, "reduce-only-pruned", eventTime,
                "trace-1");

        consumer.onOrderEvent(new ConsumerRecord<>("surprising.perp.order.events.v1", 0, 0L,
                SubscriptionTopic.WILDCARD, objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry, org.mockito.Mockito.times(2)).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getAllValues().get(0).channel()).isEqualTo(WsChannel.ORDERS);
        assertThat(topic.getAllValues().get(0).symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getAllValues().get(0).userId()).isEqualTo(1001L);
        assertThat(payload.getAllValues().get(0)).isEqualTo(event);
        assertThat(topic.getAllValues().get(1).channel()).isEqualTo(WsChannel.EXECUTION_REPORTS);
        assertThat(topic.getAllValues().get(1).symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getAllValues().get(1).userId()).isEqualTo(1001L);
        ExecutionReportEvent report = (ExecutionReportEvent) payload.getAllValues().get(1);
        assertThat(report.reportType()).isEqualTo("ORDER_EVENT");
        assertThat(report.orderId()).isEqualTo(11L);
        assertThat(report.orderEventType()).isEqualTo("CANCEL_REQUESTED");
        assertThat(report.orderStatus()).isEqualTo("CANCEL_REQUESTED");
        assertThat(report.reason()).isEqualTo("reduce-only-pruned");
        assertThat(report.traceId()).isEqualTo("trace-1");
    }

    @Test
    void fansOutTriggerOrderStatusSnapshotToTheOwningUser() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        TriggerOrderResponse order = new TriggerOrderResponse(
                501L, 1001L, "sl-1", null, "BTC-USDT", OrderSide.SELL,
                TriggerOrderType.STOP_LOSS, TriggerCondition.LESS_OR_EQUAL,
                60_000L, OrderType.MARKET, TimeInForce.IOC, 0L, 10L, MarginMode.CROSS,
                PositionSide.NET, TriggerOrderStatus.CANCELED, null, null, null,
                "POSITION_CLOSED", "trace-trigger", null, null, eventTime.minusSeconds(60), eventTime);
        TriggerOrderUpdatedEvent event = new TriggerOrderUpdatedEvent(
                701L, ProductLine.LINEAR_PERPETUAL, order, eventTime, "trace-trigger");

        consumer.onTriggerOrderEvent(new ConsumerRecord<>("surprising.perp.trigger-order.events.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.TRIGGER_ORDERS);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void fansOutMatchResultAsExecutionReport() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        MatchResultEvent event = new MatchResultEvent(71L, 202L, 2002L, "BTC-USDT", 7L,
                OrderCommandType.PLACE, "SUCCESS", 3L, OrderStatus.FILLED, eventTime, List.of(),
                "trace-result-1");

        consumer.onMatchResult(new ConsumerRecord<>("surprising.perp.match.results.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry, org.mockito.Mockito.times(2)).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getAllValues().get(0).channel()).isEqualTo(WsChannel.MATCHES);
        assertThat(topic.getAllValues().get(0).userId()).isEqualTo(2002L);
        assertThat(payload.getAllValues().get(0)).isEqualTo(event);
        assertThat(topic.getAllValues().get(1).channel()).isEqualTo(WsChannel.EXECUTION_REPORTS);
        assertThat(topic.getAllValues().get(1).symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.getAllValues().get(1).userId()).isEqualTo(2002L);
        ExecutionReportEvent report = (ExecutionReportEvent) payload.getAllValues().get(1);
        assertThat(report.reportType()).isEqualTo("MATCH_RESULT");
        assertThat(report.commandId()).isEqualTo(71L);
        assertThat(report.orderId()).isEqualTo(202L);
        assertThat(report.instrumentVersion()).isEqualTo(7L);
        assertThat(report.commandType()).isEqualTo("PLACE");
        assertThat(report.orderStatus()).isEqualTo("FILLED");
        assertThat(report.resultCode()).isEqualTo("SUCCESS");
        assertThat(report.filledQuantitySteps()).isEqualTo(3L);
        assertThat(report.traceId()).isEqualTo("trace-result-1");
    }

    @Test
    void fansOutPrivateAccountRiskToWildcardTopic() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        RiskAccountUpdatedEvent event = new RiskAccountUpdatedEvent(1L, 10L, 1001L, "USDT",
                1_000_000L, 25_000L, 1_025_000L, 100_000L, 97_560L, RiskStatus.NORMAL, eventTime);

        consumer.onAccountRisk(new ConsumerRecord<>("surprising.risk.account.events.v1", 0, 0L,
                "1001:USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.ACCOUNT_RISK);
        assertThat(topic.getValue().symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
    }

    @Test
    void rejectsAccountRiskWhenKafkaKeyDoesNotMatchUserAndAsset() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        RiskAccountUpdatedEvent event = new RiskAccountUpdatedEvent(1L, 10L, 1001L, "USDT",
                1_000_000L, 25_000L, 1_025_000L, 100_000L, 97_560L, RiskStatus.NORMAL, eventTime,
                "trace-risk-1");

        assertThatThrownBy(() -> consumer.onAccountRisk(new ConsumerRecord<>("surprising.risk.account.events.v1",
                0, 0L, "1002:USDT", objectMapper.writeValueAsString(event))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to fanout account risk update");
        verifyNoInteractions(registry);
    }

    @Test
    void fansOutPrivatePositionBySymbolWithPositionSide() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        PositionUpdatedEvent event = new PositionUpdatedEvent(2L, 91L, 1001L, "BTC-USDT", 7L,
                MarginMode.CROSS, PositionSide.LONG, 10L, 65_000L, 0L, eventTime, "trace-position-1");

        consumer.onPosition(new ConsumerRecord<>("surprising.account.position.events.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.POSITIONS);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
        assertThat(((PositionUpdatedEvent) payload.getValue()).positionSide()).isEqualTo(PositionSide.LONG);
    }

    @Test
    void fansOutPrivatePositionRiskBySymbol() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaFanoutConsumer consumer = new KafkaFanoutConsumer(objectMapper, registry, candleUpdateCoalescer);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        RiskPositionUpdatedEvent event = new RiskPositionUpdatedEvent(2L, 10L, 1001L, "BTC-USDT",
                MarginMode.CROSS, PositionSide.SHORT, 7L, "USDT", -10L, 65_000L, 67_000L, 670_000L,
                -20_000L, 100_000L, 0L, 95_238L, RiskStatus.NORMAL, eventTime, "trace-risk-position-1");

        consumer.onPositionRisk(new ConsumerRecord<>("surprising.risk.position.events.v1", 0, 0L,
                "BTC-USDT", objectMapper.writeValueAsString(event)));

        ArgumentCaptor<SubscriptionTopic> topic = ArgumentCaptor.forClass(SubscriptionTopic.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).publish(topic.capture(), payload.capture(), eq(eventTime));
        assertThat(topic.getValue().channel()).isEqualTo(WsChannel.POSITION_RISK);
        assertThat(topic.getValue().symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.getValue().userId()).isEqualTo(1001L);
        assertThat(payload.getValue()).isEqualTo(event);
        assertThat(((RiskPositionUpdatedEvent) payload.getValue()).positionSide()).isEqualTo(PositionSide.SHORT);
    }
}
