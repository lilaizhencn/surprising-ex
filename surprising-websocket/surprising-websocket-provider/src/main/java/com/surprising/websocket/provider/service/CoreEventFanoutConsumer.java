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
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class CoreEventFanoutConsumer {

    private static final long EXECUTION_SEQUENCE_MULTIPLIER = 1_000_000L;

    private final SubscriptionRegistry registry;
    private final WebSocketProperties properties;
    private long lastAppliedExportSequence;

    public CoreEventFanoutConsumer(SubscriptionRegistry registry, WebSocketProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @KafkaListener(
            topics = "#{__listener.coreEventsTopic()}",
            groupId = "#{__listener.groupId()}",
            autoStartup = "#{__listener.corePrivateEventsEnabled()}",
            containerFactory = "webSocketCoreEventsKafkaListenerContainerFactory")
    public synchronized void onCoreEvent(ConsumerRecord<String, byte[]> record) {
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
        if (event.exportSequence() <= lastAppliedExportSequence) {
            return;
        }
        if (lastAppliedExportSequence != 0 && event.exportSequence() != lastAppliedExportSequence + 1) {
            throw new IllegalStateException("non-contiguous Core events: expected="
                    + (lastAppliedExportSequence + 1) + " actual=" + event.exportSequence());
        }

        Instant eventTime = Instant.ofEpochMilli(message.header().submittedAtEpochMillis());
        Map<Long, CoreOrderStateView> orders = new HashMap<>();
        for (CoreOrderStateView order : event.changedOrders()) {
            requireProductLine(order.productLine(), productLine);
            orders.put(order.orderId(), order);
            registry.publish(topic(WsChannel.ORDERS, order.symbol(), order.userId()),
                    new CoreOrderWebSocketEvent(event.exportSequence(), order), eventTime);
            publishOrderReport(event, order, eventTime);
        }
        publishExecutions(event, orders, eventTime);
        if (productLine != ProductLine.SPOT) {
            publishPositions(event, orders, productLine, eventTime);
        }
        lastAppliedExportSequence = event.exportSequence();
    }

    private void publishExecutions(CoreExportEvent event, Map<Long, CoreOrderStateView> orders, Instant eventTime) {
        for (int index = 0; index < event.executions().size(); index++) {
            CoreExecutionView execution = event.executions().get(index);
            CoreOrderStateView taker = requireOrder(orders, execution.takerOrderId());
            CoreOrderStateView maker = requireOrder(orders, execution.makerOrderId());
            long executionSequence = Math.addExact(
                    Math.multiplyExact(event.exportSequence(), EXECUTION_SEQUENCE_MULTIPLIER), index);
            publishTradeReport(event, execution, taker, maker, executionSequence, "TAKER", eventTime);
            publishTradeReport(event, execution, maker, taker, executionSequence, "MAKER", eventTime);
        }
    }

    private void publishPositions(CoreExportEvent event, Map<Long, CoreOrderStateView> orders,
                                  ProductLine productLine, Instant eventTime) {
        Map<Long, CoreUserStateView> users = new HashMap<>();
        for (CoreUserStateView user : event.changedUsers()) {
            requireProductLine(user.productLine(), productLine);
            users.put(user.userId(), user);
            for (CorePositionView position : user.positions()) {
                publishPosition(event.exportSequence(), user, position, eventTime);
            }
        }
        Set<UserSymbol> activePositions = new HashSet<>();
        users.values().forEach(user -> user.positions().forEach(position ->
                activePositions.add(new UserSymbol(user.userId(), position.symbol()))));
        for (CoreOrderStateView order : orders.values()) {
            CoreUserStateView user = users.get(order.userId());
            UserSymbol key = new UserSymbol(order.userId(), order.symbol());
            if (user != null && activePositions.add(key)) {
                publishPosition(event.exportSequence(), user,
                        new CorePositionView(order.symbol(), "", order.marginMode(), order.positionSide(),
                                order.instrumentVersion(), 0, 0, 0, 0, 0), eventTime);
            }
        }
    }

    private void publishPosition(long exportSequence, CoreUserStateView user, CorePositionView position,
                                 Instant eventTime) {
        registry.publish(topic(WsChannel.POSITIONS, position.symbol(), user.userId()),
                new CorePositionWebSocketEvent(exportSequence, user.productLine(), user.userId(), user.revision(),
                        position.symbol(), position.instrumentVersion(), position.marginMode().name(),
                        position.positionSide().name(), position.signedQuantitySteps(), position.entryPriceTicks(),
                        position.entryValueTicks(), position.realizedPnlUnits(), position.marginAsset(),
                        position.positionMarginUnits()), eventTime);
    }

    private void publishOrderReport(CoreExportEvent event, CoreOrderStateView order, Instant eventTime) {
        registry.publish(topic(WsChannel.EXECUTION_REPORTS, order.symbol(), order.userId()),
                new ExecutionReportEvent("CORE_ORDER", order.userId(), order.symbol(), order.orderId(), null,
                        null, null, null, order.instrumentVersion(), null, event.commandType().name(), order.status(),
                        event.resultCode().name(), null, order.side().name(), order.marginMode().name(),
                        order.positionSide().name(), order.priceTicks(), order.quantitySteps(),
                        order.executedQuantitySteps(), !"OPEN".equals(order.status()), null,
                        event.commandId().toString(), eventTime), eventTime);
    }

    private void publishTradeReport(CoreExportEvent event, CoreExecutionView execution,
                                    CoreOrderStateView order, CoreOrderStateView counterparty,
                                    long executionSequence, String liquidityRole, Instant eventTime) {
        registry.publish(topic(WsChannel.EXECUTION_REPORTS, order.symbol(), order.userId()),
                new ExecutionReportEvent("TRADE", order.userId(), order.symbol(), order.orderId(), null,
                        executionSequence, counterparty.orderId(), counterparty.userId(), order.instrumentVersion(),
                        null, event.commandType().name(), order.status(), event.resultCode().name(), liquidityRole,
                        order.side().name(), order.marginMode().name(), order.positionSide().name(),
                        execution.priceTicks(), execution.quantitySteps(), order.executedQuantitySteps(),
                        !"OPEN".equals(order.status()), null, event.commandId().toString(), eventTime), eventTime);
    }

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

    public boolean corePrivateEventsEnabled() {
        return properties.getKafka().isCorePrivateEventsEnabled();
    }

    public record CoreOrderWebSocketEvent(long exportSequence, CoreOrderStateView order) {
    }

    public record CorePositionWebSocketEvent(long exportSequence, ProductLine productLine, long userId,
                                             long revision, String symbol, long instrumentVersion,
                                             String marginMode, String positionSide, long signedQuantitySteps,
                                             long entryPriceTicks, long entryValueTicks, long realizedPnlUnits,
                                             String marginAsset, long marginUnits) {
    }

    private record UserSymbol(long userId, String symbol) {
    }
}
