package com.surprising.websocket.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.trading.api.KafkaSymbolKeyValidator;
import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderBookDepthEvent;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.websocket.api.model.ExecutionReportEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes domain events and pushes them only to clients connected to this node.
 *
 * <p>Each WebSocket node intentionally uses its own Kafka consumer group. Public market data must
 * reach every node for local fanout; private account/order/position events are filtered again by
 * authenticated user subscriptions before leaving the process.</p>
 */
@Service
public class KafkaFanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaFanoutConsumer.class);

    private final ObjectMapper objectMapper;
    private final SubscriptionRegistry registry;
    private final CandleUpdateCoalescer candleUpdateCoalescer;
    private final WebSocketProperties properties;

    public KafkaFanoutConsumer(ObjectMapper objectMapper,
                               SubscriptionRegistry registry,
                               CandleUpdateCoalescer candleUpdateCoalescer) {
        this(objectMapper, registry, candleUpdateCoalescer, new WebSocketProperties());
    }

    @Autowired
    public KafkaFanoutConsumer(ObjectMapper objectMapper,
                               SubscriptionRegistry registry,
                               CandleUpdateCoalescer candleUpdateCoalescer,
                               WebSocketProperties properties) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.candleUpdateCoalescer = candleUpdateCoalescer;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.candleTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onCandle(ConsumerRecord<String, String> record) {
        try {
            CandleUpdatedEvent event = objectMapper.readValue(record.value(), CandleUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "candle update");
            candleUpdateCoalescer.publish(event, fanoutProductLine());
        } catch (Exception ex) {
            log.error("Failed to fanout candle update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout candle update", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.tradeTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onTrade(ConsumerRecord<String, String> record) {
        try {
            TradeEvent event = objectMapper.readValue(record.value(), TradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "public trade");
            registry.publish(topic(WsChannel.TRADES, event.symbol(), null), event, event.tradeTime());
        } catch (Exception ex) {
            log.error("Failed to fanout public trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout public trade", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.orderBookDepthTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onOrderBookDepth(ConsumerRecord<String, String> record) {
        try {
            OrderBookDepthEvent event = objectMapper.readValue(record.value(), OrderBookDepthEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "order book depth");
            registry.publish(topic(WsChannel.ORDER_BOOK_DEPTH, event.symbol(), null), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout order book depth: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout order book depth", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.indexPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onIndexPrice(ConsumerRecord<String, String> record) {
        try {
            IndexPriceEvent event = objectMapper.readValue(record.value(), IndexPriceEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "index price");
            registry.publish(topic(WsChannel.INDEX_PRICE, event.symbol(), null), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout index price: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout index price", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.markPriceTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMarkPrice(ConsumerRecord<String, String> record) {
        try {
            MarkPriceEvent event = objectMapper.readValue(record.value(), MarkPriceEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "mark price");
            registry.publish(topic(WsChannel.MARK_PRICE, event.symbol(), null), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout mark price: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout mark price", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.fundingRateTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.fundingRateListenerEnabled()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onFundingRate(ConsumerRecord<String, String> record) {
        try {
            PerpFundingRateEvent event = objectMapper.readValue(record.value(), PerpFundingRateEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "funding rate");
            registry.publish(topic(WsChannel.FUNDING_RATE, event.symbol(), null), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout funding rate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout funding rate", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.orderEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        try {
            OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "order event");
            registry.publish(topic(WsChannel.ORDERS, event.symbol(), event.userId()), event, event.eventTime());
            publishExecutionReport(fromOrderEvent(event));
        } catch (Exception ex) {
            log.error("Failed to fanout order event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout order event", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.matchResultsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMatchResult(ConsumerRecord<String, String> record) {
        try {
            MatchResultEvent event = objectMapper.readValue(record.value(), MatchResultEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "match result");
            registry.publish(topic(WsChannel.MATCHES, event.symbol(), event.userId()), event, event.eventTime());
            publishExecutionReport(fromMatchResult(event));
        } catch (Exception ex) {
            log.error("Failed to fanout match result: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout match result", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.matchTradesTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMatchTrade(ConsumerRecord<String, String> record) {
        try {
            MatchTradeEvent event = objectMapper.readValue(record.value(), MatchTradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "match trade");
            registry.publish(topic(WsChannel.TRADES, event.symbol(), null), event, event.eventTime());
            registry.publish(topic(WsChannel.MATCHES, event.symbol(), event.takerUserId()), event, event.eventTime());
            registry.publish(topic(WsChannel.MATCHES, event.symbol(), event.makerUserId()), event, event.eventTime());
            publishExecutionReport(fromTakerTrade(event));
            publishExecutionReport(fromMakerTrade(event));
        } catch (Exception ex) {
            log.error("Failed to fanout match trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout match trade", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.positionEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onPosition(ConsumerRecord<String, String> record) {
        try {
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position update");
            registry.publish(topic(WsChannel.POSITIONS, event.symbol(), event.userId()), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout position update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout position update", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.accountRiskEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onAccountRisk(ConsumerRecord<String, String> record) {
        try {
            RiskAccountUpdatedEvent event = objectMapper.readValue(record.value(), RiskAccountUpdatedEvent.class);
            requireMatchingAccountRiskKey(record.key(), event);
            registry.publish(topic(WsChannel.ACCOUNT_RISK, SubscriptionTopic.WILDCARD, event.userId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout account risk update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout account risk update", ex);
        }
    }

    @KafkaListener(
            topics = "#{__listener.positionRiskEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onPositionRisk(ConsumerRecord<String, String> record) {
        try {
            RiskPositionUpdatedEvent event = objectMapper.readValue(record.value(), RiskPositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position risk update");
            registry.publish(topic(WsChannel.POSITION_RISK, event.symbol(), event.userId()), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout position risk update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout position risk update", ex);
        }
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    public String candleTopic() {
        return properties.getKafka().getCandleTopic();
    }

    public String tradeTopic() {
        return properties.getKafka().getTradeTopic();
    }

    public String orderBookDepthTopic() {
        return properties.getKafka().getOrderBookDepthTopic();
    }

    public String indexPriceTopic() {
        return properties.getKafka().getIndexPriceTopic();
    }

    public String markPriceTopic() {
        return properties.getKafka().getMarkPriceTopic();
    }

    public String fundingRateTopic() {
        return properties.getKafka().getFundingRateTopic();
    }

    public boolean fundingRateListenerEnabled() {
        return properties.getKafka().isFundingRateTopicEnabled();
    }

    public String orderEventsTopic() {
        return properties.getKafka().getOrderEventsTopic();
    }

    public String matchResultsTopic() {
        return properties.getKafka().getMatchResultsTopic();
    }

    public String matchTradesTopic() {
        return properties.getKafka().getMatchTradesTopic();
    }

    public String positionEventsTopic() {
        return properties.getKafka().getPositionEventsTopic();
    }

    public String accountRiskEventsTopic() {
        return properties.getKafka().getAccountRiskEventsTopic();
    }

    public String positionRiskEventsTopic() {
        return properties.getKafka().getPositionRiskEventsTopic();
    }

    private void requireMatchingAccountRiskKey(String recordKey, RiskAccountUpdatedEvent event) {
        String expected = event.userId() + ":" + event.accountType() + ":" + event.settleAsset();
        String legacyExpected = event.userId() + ":" + event.settleAsset();
        String normalizedKey = recordKey == null ? "" : recordKey.trim();
        if (!expected.equalsIgnoreCase(normalizedKey) && !legacyExpected.equalsIgnoreCase(normalizedKey)) {
            throw new IllegalArgumentException("account risk key mismatch: expected=" + expected
                    + ", actual=" + recordKey);
        }
    }

    private void publishExecutionReport(ExecutionReportEvent report) {
        registry.publish(topic(WsChannel.EXECUTION_REPORTS, report.symbol(), report.userId()),
                report, report.eventTime());
    }

    private SubscriptionTopic topic(WsChannel channel, String symbol, Long userId) {
        return new SubscriptionTopic(channel, symbol, null, userId, fanoutProductLine());
    }

    private ProductLine fanoutProductLine() {
        return properties.getKafka().isProductTopicsEnabled() ? properties.getKafka().getProductLine() : null;
    }

    private ExecutionReportEvent fromOrderEvent(OrderEvent event) {
        return new ExecutionReportEvent(
                "ORDER_EVENT",
                event.userId(),
                event.symbol(),
                event.orderId(),
                null,
                null,
                null,
                null,
                null,
                name(event.eventType()),
                null,
                name(event.status()),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                event.reason(),
                event.traceId(),
                event.eventTime());
    }

    private ExecutionReportEvent fromMatchResult(MatchResultEvent event) {
        return new ExecutionReportEvent(
                "MATCH_RESULT",
                event.userId(),
                event.symbol(),
                event.orderId(),
                event.commandId(),
                null,
                null,
                null,
                event.instrumentVersion(),
                null,
                name(event.commandType()),
                name(event.orderStatus()),
                event.resultCode(),
                null,
                null,
                null,
                null,
                null,
                null,
                event.filledQuantitySteps(),
                null,
                null,
                event.traceId(),
                event.eventTime());
    }

    private ExecutionReportEvent fromTakerTrade(MatchTradeEvent event) {
        return new ExecutionReportEvent(
                "TRADE",
                event.takerUserId(),
                event.symbol(),
                event.takerOrderId(),
                event.commandId(),
                event.tradeId(),
                event.makerOrderId(),
                event.makerUserId(),
                event.takerInstrumentVersion(),
                null,
                null,
                null,
                null,
                "TAKER",
                name(event.takerSide()),
                name(event.takerMarginMode()),
                name(event.takerPositionSide()),
                event.priceTicks(),
                event.quantitySteps(),
                event.quantitySteps(),
                event.takerOrderCompleted(),
                null,
                event.traceId(),
                event.eventTime());
    }

    private ExecutionReportEvent fromMakerTrade(MatchTradeEvent event) {
        return new ExecutionReportEvent(
                "TRADE",
                event.makerUserId(),
                event.symbol(),
                event.makerOrderId(),
                event.commandId(),
                event.tradeId(),
                event.takerOrderId(),
                event.takerUserId(),
                event.makerInstrumentVersion(),
                null,
                null,
                null,
                null,
                "MAKER",
                name(opposite(event.takerSide())),
                name(event.makerMarginMode()),
                name(event.makerPositionSide()),
                event.priceTicks(),
                event.quantitySteps(),
                event.quantitySteps(),
                event.makerOrderCompleted(),
                null,
                event.traceId(),
                event.eventTime());
    }

    private OrderSide opposite(OrderSide side) {
        if (side == OrderSide.BUY) {
            return OrderSide.SELL;
        }
        if (side == OrderSide.SELL) {
            return OrderSide.BUY;
        }
        throw new IllegalArgumentException("unsupported order side: " + side);
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
