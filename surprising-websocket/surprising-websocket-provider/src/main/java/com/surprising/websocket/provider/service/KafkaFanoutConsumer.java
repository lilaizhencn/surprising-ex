package com.surprising.websocket.provider.service;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.candlestick.api.model.CandleUpdatedEvent;
import com.surprising.candlestick.api.model.TradeEvent;
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
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public KafkaFanoutConsumer(ObjectMapper objectMapper,
                               SubscriptionRegistry registry,
                               CandleUpdateCoalescer candleUpdateCoalescer) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.candleUpdateCoalescer = candleUpdateCoalescer;
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.candle-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onCandle(ConsumerRecord<String, String> record) {
        try {
            CandleUpdatedEvent event = objectMapper.readValue(record.value(), CandleUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "candle update");
            candleUpdateCoalescer.publish(event);
        } catch (Exception ex) {
            log.error("Failed to fanout candle update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout candle update", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.trade-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onTrade(ConsumerRecord<String, String> record) {
        try {
            TradeEvent event = objectMapper.readValue(record.value(), TradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "public trade");
            registry.publish(new SubscriptionTopic(WsChannel.TRADES, event.symbol(), null, null),
                    event, event.tradeTime());
        } catch (Exception ex) {
            log.error("Failed to fanout public trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout public trade", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.order-book-depth-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onOrderBookDepth(ConsumerRecord<String, String> record) {
        try {
            OrderBookDepthEvent event = objectMapper.readValue(record.value(), OrderBookDepthEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "order book depth");
            registry.publish(new SubscriptionTopic(WsChannel.ORDER_BOOK_DEPTH, event.symbol(), null, null),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout order book depth: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout order book depth", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.index-price-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onIndexPrice(ConsumerRecord<String, String> record) {
        try {
            IndexPriceEvent event = objectMapper.readValue(record.value(), IndexPriceEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "index price");
            registry.publish(new SubscriptionTopic(WsChannel.INDEX_PRICE, event.symbol(), null, null),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout index price: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout index price", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.mark-price-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMarkPrice(ConsumerRecord<String, String> record) {
        try {
            MarkPriceEvent event = objectMapper.readValue(record.value(), MarkPriceEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "mark price");
            registry.publish(new SubscriptionTopic(WsChannel.MARK_PRICE, event.symbol(), null, null),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout mark price: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout mark price", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.funding-rate-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onFundingRate(ConsumerRecord<String, String> record) {
        try {
            PerpFundingRateEvent event = objectMapper.readValue(record.value(), PerpFundingRateEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "funding rate");
            registry.publish(new SubscriptionTopic(WsChannel.FUNDING_RATE, event.symbol(), null, null),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout funding rate: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout funding rate", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.order-events-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        try {
            OrderEvent event = objectMapper.readValue(record.value(), OrderEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "order event");
            registry.publish(new SubscriptionTopic(WsChannel.ORDERS, event.symbol(), null, event.userId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout order event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout order event", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.match-results-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMatchResult(ConsumerRecord<String, String> record) {
        try {
            MatchResultEvent event = objectMapper.readValue(record.value(), MatchResultEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "match result");
            registry.publish(new SubscriptionTopic(WsChannel.MATCHES, event.symbol(), null, event.userId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout match result: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout match result", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.match-trades-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onMatchTrade(ConsumerRecord<String, String> record) {
        try {
            MatchTradeEvent event = objectMapper.readValue(record.value(), MatchTradeEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "match trade");
            registry.publish(new SubscriptionTopic(WsChannel.MATCHES, event.symbol(), null, event.takerUserId()),
                    event, event.eventTime());
            registry.publish(new SubscriptionTopic(WsChannel.MATCHES, event.symbol(), null, event.makerUserId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout match trade: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout match trade", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.position-events-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onPosition(ConsumerRecord<String, String> record) {
        try {
            PositionUpdatedEvent event = objectMapper.readValue(record.value(), PositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position update");
            registry.publish(new SubscriptionTopic(WsChannel.POSITIONS, event.symbol(), null, event.userId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout position update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout position update", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.account-risk-events-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onAccountRisk(ConsumerRecord<String, String> record) {
        try {
            RiskAccountUpdatedEvent event = objectMapper.readValue(record.value(), RiskAccountUpdatedEvent.class);
            requireMatchingAccountRiskKey(record.key(), event);
            registry.publish(new SubscriptionTopic(WsChannel.ACCOUNT_RISK, SubscriptionTopic.WILDCARD, null,
                    event.userId()), event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout account risk update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout account risk update", ex);
        }
    }

    @KafkaListener(
            topics = "${surprising.websocket.kafka.position-risk-events-topic}",
            groupId = "${surprising.websocket.kafka.group-id}",
            containerFactory = "webSocketKafkaListenerContainerFactory")
    public void onPositionRisk(ConsumerRecord<String, String> record) {
        try {
            RiskPositionUpdatedEvent event = objectMapper.readValue(record.value(), RiskPositionUpdatedEvent.class);
            KafkaSymbolKeyValidator.requireMatchingSymbol(record.key(), event.symbol(), "position risk update");
            registry.publish(new SubscriptionTopic(WsChannel.POSITION_RISK, event.symbol(), null, event.userId()),
                    event, event.eventTime());
        } catch (Exception ex) {
            log.error("Failed to fanout position risk update: {}", ex.getMessage(), ex);
            throw new IllegalStateException("failed to fanout position risk update", ex);
        }
    }

    private void requireMatchingAccountRiskKey(String recordKey, RiskAccountUpdatedEvent event) {
        String expected = event.userId() + ":" + event.settleAsset();
        if (recordKey == null || !expected.equalsIgnoreCase(recordKey.trim())) {
            throw new IllegalArgumentException("account risk key mismatch: expected=" + expected
                    + ", actual=" + recordKey);
        }
    }
}
