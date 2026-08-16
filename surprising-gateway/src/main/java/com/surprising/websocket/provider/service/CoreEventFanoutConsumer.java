package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.ExecutionReportEvent;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TriggerCondition;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderType;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CoreEventFanoutConsumer {

    private static final long EXECUTION_SEQUENCE_MULTIPLIER = 1_000_000L;

    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private final CoreEventAuditRepository auditRepository;
    private long lastProcessedKafkaOffset = -1L;
    private String lastProcessedKafkaKey;
    private byte[] lastProcessedKafkaValue;

    @Autowired
    public CoreEventFanoutConsumer(SubscriptionRegistry registry, WebSocketProperties properties,
                                   CoreEventAuditRepository auditRepository) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
    }

    @KafkaListener(
            topics = "#{__listener.coreEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            containerFactory = "webSocketCoreEventsKafkaListenerContainerFactory")
    public synchronized void onCoreEvent(ConsumerRecord<String, byte[]> record) {
        CoreEvent coreEvent = decodeAndValidate(record);
        validateKafkaOffset(record);
        auditRepository.record(coreEvent.productLine(), coreEvent.event(), record.value(),
                coreEvent.message().header().submittedAtEpochMillis());
        fanout(coreEvent);
        if (record.offset() >= 0L) {
            lastProcessedKafkaOffset = record.offset();
            lastProcessedKafkaKey = record.key();
            lastProcessedKafkaValue = record.value().clone();
        }
    }

    private CoreEvent decodeAndValidate(ConsumerRecord<String, byte[]> record) {
        if (record.partition() != 0) {
            throw new IllegalStateException("Core events topic must have exactly one partition");
        }
        if (!coreEventsTopic().equals(record.topic())) {
            throw new IllegalStateException("unexpected Core events topic " + record.topic());
        }
        CoreMessage message = CoreMessageCodec.decode(record.value());
        ProductLine productLine = properties.getKafka().getProductLine();
        if (message.header().productLine() != productLine
                || message.header().kind() != WireMessageKind.EXPORT_EVENT
                || message.header().messageType() != CoreMessageType.CORE_EVENT) {
            throw new IllegalStateException("invalid Core event envelope for WebSocket fanout");
        }
        CoreExportEvent event = CoreExportCodec.decodeEvent(message.payload());
        String expectedKey = productLine.name() + ":" + event.exportSequence();
        if (!expectedKey.equals(record.key())) {
            throw new IllegalStateException("Core event key mismatch: expected=" + expectedKey
                    + " actual=" + record.key());
        }
        return new CoreEvent(productLine, message, event);
    }

    private void validateKafkaOffset(ConsumerRecord<String, byte[]> record) {
        long offset = record.offset();
        if (offset < 0L || lastProcessedKafkaOffset < 0L) {
            return;
        }
        if (offset == lastProcessedKafkaOffset) {
            if (!Objects.equals(record.key(), lastProcessedKafkaKey)
                    || !Arrays.equals(record.value(), lastProcessedKafkaValue)) {
                throw new IllegalStateException("Kafka Core event replay identity mismatch at offset=" + offset);
            }
            return;
        }
        if (offset != lastProcessedKafkaOffset + 1L) {
            throw new IllegalStateException("non-contiguous Kafka Core event offsets: expected="
                    + (lastProcessedKafkaOffset + 1L) + " actual=" + offset);
        }
    }

    private void fanout(CoreEvent coreEvent) {
        CoreMessage message = coreEvent.message();
        CoreExportEvent event = coreEvent.event();
        ProductLine productLine = coreEvent.productLine();
        Instant eventTime = Instant.ofEpochMilli(message.header().submittedAtEpochMillis());
        Map<Long, CoreOrderStateView> orders = new HashMap<>();
        for (int index = 0; index < event.changedOrders().size(); index++) {
            CoreOrderStateView order = event.changedOrders().get(index);
            requireProductLine(order.productLine(), productLine);
            orders.put(order.orderId(), order);
            registry.publish(topic(WsChannel.ORDERS, order.symbol(), order.userId()),
                    new CoreOrderWebSocketEvent(eventId(productLine, event.exportSequence(),
                            CoreWebSocketEventId.EventKind.ORDER, order.userId() + "/" + order.orderId(), index),
                            event.exportSequence(), order), eventTime);
            publishOrderReport(event, order, index, eventTime);
        }
        publishExecutions(event, orders, eventTime);
        for (int index = 0; index < event.changedTriggerOrders().size(); index++) {
            var trigger = event.changedTriggerOrders().get(index);
            TriggerOrderResponse response = triggerResponse(trigger);
            registry.publish(topic(WsChannel.TRIGGER_ORDERS, trigger.symbol(), trigger.userId()),
                    new CoreTriggerOrderWebSocketEvent(eventId(productLine, event.exportSequence(),
                            CoreWebSocketEventId.EventKind.TRIGGER_ORDER,
                            trigger.userId() + "/" + trigger.triggerOrderId(), index),
                            new TriggerOrderUpdatedEvent(Math.addExact(Math.multiplyExact(event.exportSequence(),
                                    1_000_000L), index), productLine, response, eventTime, trigger.traceId())), eventTime);
        }
        if (productLine != ProductLine.SPOT) {
            publishPositions(event, orders, productLine, eventTime);
        }
    }

    private static String eventId(ProductLine productLine, long exportSequence,
                                  CoreWebSocketEventId.EventKind eventKind,
                                  String discriminator, int itemIndex) {
        return CoreWebSocketEventId.of(productLine, exportSequence, eventKind, discriminator, itemIndex);
    }

    private void publishExecutions(CoreExportEvent event, Map<Long, CoreOrderStateView> orders, Instant eventTime) {
        for (int index = 0; index < event.executions().size(); index++) {
            CoreExecutionView execution = event.executions().get(index);
            CoreOrderStateView taker = requireOrder(orders, execution.takerOrderId());
            CoreOrderStateView maker = requireOrder(orders, execution.makerOrderId());
            long executionSequence = Math.addExact(
                    Math.multiplyExact(event.exportSequence(), EXECUTION_SEQUENCE_MULTIPLIER), index);
            publishTradeReport(event, execution, taker, maker, executionSequence, "TAKER", index, eventTime);
            publishTradeReport(event, execution, maker, taker, executionSequence, "MAKER", index, eventTime);
        }
    }

    private void publishPositions(CoreExportEvent event, Map<Long, CoreOrderStateView> orders,
                                  ProductLine productLine, Instant eventTime) {
        Map<Long, CoreUserStateView> users = new HashMap<>();
        for (CoreUserStateView user : event.changedUsers()) {
            requireProductLine(user.productLine(), productLine);
            users.put(user.userId(), user);
            for (int index = 0; index < user.positions().size(); index++) {
                publishPosition(event.exportSequence(), user, index, user.positions().get(index), eventTime);
            }
        }
        Set<UserSymbol> activePositions = new HashSet<>();
        users.values().forEach(user -> user.positions().forEach(position ->
                activePositions.add(new UserSymbol(user.userId(), position.symbol()))));
        for (int index = 0; index < event.changedOrders().size(); index++) {
            CoreOrderStateView order = event.changedOrders().get(index);
            CoreUserStateView user = users.get(order.userId());
            UserSymbol key = new UserSymbol(order.userId(), order.symbol());
            if (user != null && activePositions.add(key)) {
                publishPosition(event.exportSequence(), user, index,
                        new CorePositionView(order.symbol(), "", order.marginMode(), order.positionSide(),
                                order.instrumentVersion(), 0, 0, 0, 0, 0), eventTime);
            }
        }
    }

    private void publishPosition(long exportSequence, CoreUserStateView user, int itemIndex,
                                 CorePositionView position, Instant eventTime) {
        String eventId = eventId(user.productLine(), exportSequence, CoreWebSocketEventId.EventKind.POSITION,
                user.userId() + "/" + position.symbol() + "/" + position.positionSide().name(), itemIndex);
        registry.publish(topic(WsChannel.POSITIONS, position.symbol(), user.userId()),
                new CorePositionWebSocketEvent(eventId, exportSequence, user.productLine(), user.userId(), user.revision(),
                        position.symbol(), position.instrumentVersion(), position.marginMode().name(),
                        position.positionSide().name(), position.signedQuantitySteps(), position.entryPriceTicks(),
                        position.entryValueTicks(), position.realizedPnlUnits(), position.marginAsset(),
                        position.positionMarginUnits()), eventTime);
    }

    private void publishOrderReport(CoreExportEvent event, CoreOrderStateView order, int itemIndex,
                                    Instant eventTime) {
        String eventId = eventId(properties.getKafka().getProductLine(), event.exportSequence(),
                CoreWebSocketEventId.EventKind.EXECUTION_REPORT,
                order.userId() + "/" + order.orderId() + "/ORDER", itemIndex);
        registry.publish(topic(WsChannel.EXECUTION_REPORTS, order.symbol(), order.userId()),
                new CoreExecutionWebSocketEvent(eventId,
                        new ExecutionReportEvent("CORE_ORDER", order.userId(), order.symbol(), order.orderId(), null,
                                null, null, null, order.instrumentVersion(), null, event.commandType().name(),
                                order.status(), event.resultCode().name(), null, order.side().name(),
                                order.marginMode().name(), order.positionSide().name(), order.priceTicks(),
                                order.quantitySteps(), order.executedQuantitySteps(), !"OPEN".equals(order.status()),
                                null, event.commandId().toString(), eventTime)), eventTime);
    }

    private void publishTradeReport(CoreExportEvent event, CoreExecutionView execution,
                                    CoreOrderStateView order, CoreOrderStateView counterparty,
                                    long executionSequence, String liquidityRole, int itemIndex,
                                    Instant eventTime) {
        String eventId = eventId(properties.getKafka().getProductLine(), event.exportSequence(),
                CoreWebSocketEventId.EventKind.EXECUTION_REPORT,
                order.userId() + "/" + order.orderId() + "/" + liquidityRole, itemIndex);
        registry.publish(topic(WsChannel.EXECUTION_REPORTS, order.symbol(), order.userId()),
                new CoreExecutionWebSocketEvent(eventId,
                        new ExecutionReportEvent("TRADE", order.userId(), order.symbol(), order.orderId(), null,
                                executionSequence, counterparty.orderId(), counterparty.userId(), order.instrumentVersion(),
                                null, event.commandType().name(), order.status(), event.resultCode().name(), liquidityRole,
                                order.side().name(), order.marginMode().name(), order.positionSide().name(),
                                execution.priceTicks(), execution.quantitySteps(), order.executedQuantitySteps(),
                                !"OPEN".equals(order.status()), null, event.commandId().toString(), eventTime)), eventTime);
    }

    private static TriggerOrderResponse triggerResponse(com.surprising.aeron.protocol.CoreTriggerOrderStateView value) {
        return new TriggerOrderResponse(value.triggerOrderId(), value.userId(), empty(value.clientTriggerOrderId()),
                empty(value.ocoGroupId()), value.symbol(), OrderSide.valueOf(value.side().name()),
                TriggerOrderType.valueOf(value.triggerType().name()), TriggerCondition.valueOf(value.triggerCondition().name()),
                value.triggerPriceTicks(), nullable(value.activationPriceTicks()), nullable(value.callbackRatePpm()),
                nullable(value.highestPriceTicks()), nullable(value.lowestPriceTicks()), instant(value.activatedAtEpochMillis()),
                OrderType.valueOf(value.orderType().name()), TimeInForce.valueOf(value.timeInForce().name()), value.priceTicks(),
                value.quantitySteps(), MarginMode.valueOf(value.marginMode().name()), PositionSide.valueOf(value.positionSide().name()),
                TriggerOrderStatus.valueOf(value.status().name()), nullable(value.placedOrderId()), nullable(value.triggerSequence()),
                nullable(value.triggeredPriceTicks()), empty(value.rejectReason()), empty(value.traceId()), instant(value.expiresAtEpochMillis()),
                instant(value.triggeredAtEpochMillis()), instant(value.createdAtEpochMillis()), instant(value.updatedAtEpochMillis()));
    }
    private static Long nullable(long value) { return value == 0 ? null : value; }
    private static Instant instant(long value) { return value == 0 ? null : Instant.ofEpochMilli(value); }
    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }

    private SubscriptionTopic topic(WsChannel channel, String symbol, long userId) {
        return new SubscriptionTopic(channel, symbol, null, userId, properties.getKafka().getProductLine());
    }

    private static CoreOrderStateView requireOrder(Map<Long, CoreOrderStateView> orders, long orderId) {
        CoreOrderStateView order = orders.get(orderId);
        if (order == null) {
            throw new IllegalStateException("execution order metadata is missing from Core event: " + orderId);
        }
        return order;
    }

    private static void requireProductLine(ProductLine actual, ProductLine expected) {
        if (actual != expected) {
            throw new IllegalStateException("Core state product line mismatch");
        }
    }

    public String coreEventsTopic() {
        return properties.getKafka().getCoreEventsTopic();
    }

    public String groupId() {
        return properties.getKafka().getGroupId();
    }

    public record CoreOrderWebSocketEvent(String eventId, long exportSequence, CoreOrderStateView order) {
        public CoreOrderWebSocketEvent(long exportSequence, CoreOrderStateView order) {
            this(CoreWebSocketEventId.of(order.productLine(), exportSequence,
                    CoreWebSocketEventId.EventKind.ORDER, order.userId() + "/" + order.orderId(), 0),
                    exportSequence, order);
        }
    }

    public record CorePositionWebSocketEvent(String eventId, long exportSequence, ProductLine productLine, long userId,
                                             long revision, String symbol, long instrumentVersion,
                                             String marginMode, String positionSide, long signedQuantitySteps,
                                             long entryPriceTicks, long entryValueTicks, long realizedPnlUnits,
                                             String marginAsset, long marginUnits) {
        public CorePositionWebSocketEvent(long exportSequence, ProductLine productLine, long userId,
                                          long revision, String symbol, long instrumentVersion, String marginMode,
                                          String positionSide, long signedQuantitySteps, long entryPriceTicks,
                                          long entryValueTicks, long realizedPnlUnits, String marginAsset,
                                          long marginUnits) {
            this(CoreWebSocketEventId.of(productLine, exportSequence,
                    CoreWebSocketEventId.EventKind.POSITION,
                    userId + "/" + symbol + "/" + positionSide, 0), exportSequence, productLine, userId,
                    revision, symbol, instrumentVersion, marginMode, positionSide, signedQuantitySteps,
                    entryPriceTicks, entryValueTicks, realizedPnlUnits, marginAsset, marginUnits);
        }
    }

    public record CoreExecutionWebSocketEvent(String eventId, ExecutionReportEvent report) {
    }

    public record CoreTriggerOrderWebSocketEvent(String eventId, TriggerOrderUpdatedEvent event) {
    }

    private record CoreEvent(ProductLine productLine, CoreMessage message, CoreExportEvent event) {
    }

    private record UserSymbol(long userId, String symbol) {
    }
}
