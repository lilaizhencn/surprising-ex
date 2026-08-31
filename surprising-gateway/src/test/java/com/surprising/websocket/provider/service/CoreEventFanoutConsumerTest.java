package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreExecutionView;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class CoreEventFanoutConsumerTest {

    @Test
    void fansOutAuthoritativeOrdersExecutionsAndPositionsWithStableReplayIds() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties);
        ConsumerRecord<String, byte[]> record = record(ProductLine.LINEAR_PERPETUAL, 7, true);

        consumer.onCoreEvents(List.of(record));
        consumer.onCoreEvents(List.of(record));

        ArgumentCaptor<SubscriptionTopic> topics = ArgumentCaptor.forClass(SubscriptionTopic.class);
        verify(registry, times(12)).publishTimedBatch(topics.capture(), any());
        assertThat(topics.getAllValues()).allMatch(topic -> topic.productLine() == ProductLine.LINEAR_PERPETUAL);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.channel() == WsChannel.ORDERS)).hasSize(4);
        assertThat(topics.getAllValues().stream()
                .filter(topic -> topic.channel() == WsChannel.EXECUTION_REPORTS)).hasSize(4);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.channel() == WsChannel.POSITIONS)).hasSize(4);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.userId() == 101L)).hasSize(6);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.userId() == 202L)).hasSize(6);
    }

    @Test
    void rejectsWrongProductKeyAndDoesNotPublishSpotPositions() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties(ProductLine.SPOT));
        ConsumerRecord<String, byte[]> valid = record(ProductLine.SPOT, 1, false);
        ConsumerRecord<String, byte[]> wrongKey = new ConsumerRecord<>(valid.topic(), 0, 0,
                "LINEAR_PERPETUAL:1", valid.value());

        assertThatThrownBy(() -> consumer.onCoreEvents(List.of(wrongKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key mismatch");

        consumer.onCoreEvents(List.of(valid));
        ArgumentCaptor<SubscriptionTopic> topics = ArgumentCaptor.forClass(SubscriptionTopic.class);
        verify(registry, times(4)).publishTimedBatch(topics.capture(), any());
        assertThat(topics.getAllValues()).noneMatch(topic -> topic.channel() == WsChannel.POSITIONS);
    }

    @Test
    void restartsFromCommittedOffsetWithStableEventIds() {
        SubscriptionRegistry firstRegistry = mock(SubscriptionRegistry.class);
        SubscriptionRegistry restartedRegistry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        ConsumerRecord<String, byte[]> replay = record(ProductLine.LINEAR_PERPETUAL, 7, true, 27);

        new CoreEventFanoutConsumer(firstRegistry, properties).onCoreEvents(List.of(replay));
        new CoreEventFanoutConsumer(restartedRegistry, properties).onCoreEvents(List.of(replay));

        ArgumentCaptor<List> firstPayload = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> replayedPayload = ArgumentCaptor.forClass(List.class);
        verify(firstRegistry, times(6)).publishTimedBatch(any(SubscriptionTopic.class), firstPayload.capture());
        verify(restartedRegistry, times(6)).publishTimedBatch(any(SubscriptionTopic.class), replayedPayload.capture());
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> firstJson = firstPayload.getAllValues().stream()
                .flatMap(List::stream)
                .map(value -> ((SubscriptionRegistry.TimedPayload) value).payload())
                .map(objectMapper::writeValueAsString)
                .toList();
        List<String> replayedJson = replayedPayload.getAllValues().stream()
                .flatMap(List::stream)
                .map(value -> ((SubscriptionRegistry.TimedPayload) value).payload())
                .map(objectMapper::writeValueAsString)
                .toList();
        assertThat(firstJson).allMatch(payload -> payload.contains("\"eventId\""));
        assertThat(replayedJson).isEqualTo(firstJson);
    }

    @Test
    void rejectsKafkaOffsetGapButReconstructedConsumerAcceptsCommittedStartingOffset() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.SPOT);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties);

        consumer.onCoreEvents(List.of(record(ProductLine.SPOT, 1, false, 12)));
        assertThatThrownBy(() -> consumer.onCoreEvents(List.of(record(ProductLine.SPOT, 2, false, 14))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka Core event offsets");

        new CoreEventFanoutConsumer(mock(SubscriptionRegistry.class), properties)
                .onCoreEvents(List.of(record(ProductLine.SPOT, 2, false, 14)));
    }

    @Test
    void retriesTheSameKafkaBatchWhenFanoutFailsBeforeOffsetCommit() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties(ProductLine.SPOT));
        ConsumerRecord<String, byte[]> record = record(ProductLine.SPOT, 1, false, 12);
        doThrow(new IllegalStateException("queue unavailable"))
                .doNothing()
                .when(registry).publishTimedBatch(any(SubscriptionTopic.class), any());

        assertThatThrownBy(() -> consumer.onCoreEvents(List.of(record)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queue unavailable");
        assertThatCode(() -> consumer.onCoreEvents(List.of(record))).doesNotThrowAnyException();
    }

    @Test
    void eventIdCanonicalizationSeparatesEveryCoreEventKindAndIndex() {
        String order = CoreWebSocketEventId.of(ProductLine.LINEAR_PERPETUAL, 9,
                CoreWebSocketEventId.EventKind.ORDER, "101/11", 0);
        assertThat(order).isEqualTo(CoreWebSocketEventId.of(ProductLine.LINEAR_PERPETUAL, 9,
                CoreWebSocketEventId.EventKind.ORDER, "101/11", 0));
        assertThat(order).isNotEqualTo(CoreWebSocketEventId.of(ProductLine.LINEAR_PERPETUAL, 9,
                CoreWebSocketEventId.EventKind.POSITION, "101/11", 0));
        assertThat(order).isNotEqualTo(CoreWebSocketEventId.of(ProductLine.LINEAR_PERPETUAL, 9,
                CoreWebSocketEventId.EventKind.ORDER, "101/11", 1));
        assertThat(order).isNotEqualTo(CoreWebSocketEventId.of(ProductLine.INVERSE_PERPETUAL, 9,
                CoreWebSocketEventId.EventKind.ORDER, "101/11", 0));
        assertThat(CoreWebSocketEventId.uuid(order)).isEqualTo(CoreWebSocketEventId.uuid(order));
    }

    @Test
    void rejectsMixedProductLineTriggerBeforeAnyFanout() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties(ProductLine.SPOT));

        assertThatThrownBy(() -> consumer.onCoreEvents(List.of(mixedTriggerRecord())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid Core event envelope for WebSocket fanout")
                .hasRootCauseMessage("Core export trigger order product line mismatch");
        verifyNoInteractions(registry);
    }

    private static WebSocketProperties properties(ProductLine productLine) {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setGroupId("p8-node");
        return properties;
    }

    private static ConsumerRecord<String, byte[]> record(ProductLine productLine, long sequence,
                                                          boolean includePositions) {
        return record(productLine, sequence, includePositions, 0);
    }

    private static ConsumerRecord<String, byte[]> record(ProductLine productLine, long sequence,
                                                          boolean includePositions, long kafkaOffset) {
        UUID commandId = UUID.randomUUID();
        CoreOrderStateView taker = new CoreOrderStateView(11, productLine, 101, "P8-BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 10, 10, 0, false, "FILLED", 2);
        CoreOrderStateView maker = new CoreOrderStateView(22, productLine, 202, "P8-BTC-USDT", 1,
                CoreOrderSide.SELL, 100, 10, 10, 0, false, "FILLED", 2);
        CorePositionView position = new CorePositionView("P8-BTC-USDT", "USDT", CoreMarginMode.CROSS,
                CorePositionSide.NET, 1, 10, 100, 1_000, 0, 100);
        CoreUserStateView takerUser = new CoreUserStateView(productLine, 101, 2, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 9_000, 0)), List.of(),
                includePositions ? List.of(position) : List.of());
        CoreUserStateView makerUser = new CoreUserStateView(productLine, 202, 2, CorePositionMode.ONE_WAY,
                List.of(new CoreBalanceView("USDT", 11_000, 0)), List.of(), List.of());
        byte[] payload = new byte[] {1};
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                productLine, CommandSource.GATEWAY, 1, sequence, 101, 1_700_000_000_000L, 1), payload);
        CoreExportEvent event = new CoreExportEvent(sequence, sequence, 9, commandId,
                CoreMessageType.PLACE_ORDER, ResponseStatus.APPLIED, CoreResultCode.NONE, 101, payload,
                List.of(takerUser, makerUser), List.of(taker, maker),
                List.of(new CoreExecutionView(11, 22, 101, 202, 100, 10)), List.of(), List.of(), List.of(), List.of(),
                Math.max(0, sequence - 1), 8, 9, com.surprising.aeron.protocol.CoreRoute.DEFAULT.version(),
                1, 9, sequence, com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0), sequence,
                List.of(), com.surprising.aeron.protocol.CommandFingerprint.of(command), List.of(),
                CoreExportEvent.TerminalIds.empty(), Math.max(0, sequence - 1), sequence,
                Math.max(0, sequence - 1), sequence, null, null, CoreExportEvent.Tombstones.empty());
        CoreMessage message = new CoreMessage(command.header().exportEvent(sequence), CoreExportCodec.encodeEvent(event));
        String topic = "surprising." + productLine.topicSegment() + ".core.events.v1";
        return new ConsumerRecord<>(topic, 0, kafkaOffset, productLine.name() + ":" + sequence,
                CoreMessageCodec.encode(message));
    }

    private static ConsumerRecord<String, byte[]> mixedTriggerRecord() {
        UUID commandId = UUID.randomUUID();
        byte[] payload = new byte[] {1};
        CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 0, 1_700_000_000_000L, 1), payload);
        CoreTriggerOrderStateView trigger = new CoreTriggerOrderStateView(501,
                ProductLine.LINEAR_PERPETUAL, 1001, "tp-501", "", "BTC-USDT", CoreOrderSide.SELL,
                CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0,
                CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trigger-trace", 0, 0, 1_000, 1_000, 1,
                7, -25, 40);
        CoreExportEvent event = new CoreExportEvent(1, 1, 9, commandId, CoreMessageType.PROBE_INCREMENT,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 0, payload, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(trigger), 8, 8, 9,
                com.surprising.aeron.protocol.CoreRoute.DEFAULT.version(), 1, 9, 1,
                com.surprising.aeron.protocol.CoreMatcherTransition.unchanged(0, 0), 1, List.of(),
                com.surprising.aeron.protocol.CommandFingerprint.of(command), List.of(),
                CoreExportEvent.TerminalIds.empty(), 0, 1, 0, 1, null, null,
                CoreExportEvent.Tombstones.empty());
        CoreMessage envelope = new CoreMessage(command.header().exportEvent(1), CoreExportCodec.encodeEvent(event));
        return new ConsumerRecord<>("surprising.spot.core.events.v1", 0, 0, "SPOT:1",
                CoreMessageCodec.encode(envelope));
    }
}
