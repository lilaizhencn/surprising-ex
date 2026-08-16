package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreResultCode;
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
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

class CoreEventFanoutConsumerTest {

    @Test
    void fansOutAuthoritativeOrdersExecutionsAndPositionsWithStableReplayIds() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties, auditRepository());
        ConsumerRecord<String, byte[]> record = record(ProductLine.LINEAR_PERPETUAL, 7, true);

        consumer.onCoreEvent(record);
        consumer.onCoreEvent(record);

        ArgumentCaptor<SubscriptionTopic> topics = ArgumentCaptor.forClass(SubscriptionTopic.class);
        verify(registry, times(16)).publish(topics.capture(), any(), any(Instant.class));
        assertThat(topics.getAllValues()).allMatch(topic -> topic.productLine() == ProductLine.LINEAR_PERPETUAL);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.channel() == WsChannel.ORDERS)).hasSize(4);
        assertThat(topics.getAllValues().stream()
                .filter(topic -> topic.channel() == WsChannel.EXECUTION_REPORTS)).hasSize(8);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.channel() == WsChannel.POSITIONS)).hasSize(4);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.userId() == 101L)).hasSize(8);
        assertThat(topics.getAllValues().stream().filter(topic -> topic.userId() == 202L)).hasSize(8);
    }

    @Test
    void rejectsWrongProductKeyAndDoesNotPublishSpotPositions() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties(ProductLine.SPOT),
                auditRepository());
        ConsumerRecord<String, byte[]> valid = record(ProductLine.SPOT, 1, false);
        ConsumerRecord<String, byte[]> wrongKey = new ConsumerRecord<>(valid.topic(), 0, 0,
                "LINEAR_PERPETUAL:1", valid.value());

        assertThatThrownBy(() -> consumer.onCoreEvent(wrongKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key mismatch");

        consumer.onCoreEvent(valid);
        ArgumentCaptor<SubscriptionTopic> topics = ArgumentCaptor.forClass(SubscriptionTopic.class);
        verify(registry, times(6)).publish(topics.capture(), any(), any(Instant.class));
        assertThat(topics.getAllValues()).noneMatch(topic -> topic.channel() == WsChannel.POSITIONS);
    }

    @Test
    void restartsFromCommittedOffsetWithStableEventIds() {
        SubscriptionRegistry firstRegistry = mock(SubscriptionRegistry.class);
        SubscriptionRegistry restartedRegistry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        ConsumerRecord<String, byte[]> replay = record(ProductLine.LINEAR_PERPETUAL, 7, true, 27);

        new CoreEventFanoutConsumer(firstRegistry, properties, auditRepository()).onCoreEvent(replay);
        new CoreEventFanoutConsumer(restartedRegistry, properties, auditRepository()).onCoreEvent(replay);

        ArgumentCaptor<Object> firstPayload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> replayedPayload = ArgumentCaptor.forClass(Object.class);
        verify(firstRegistry, times(8)).publish(any(SubscriptionTopic.class), firstPayload.capture(),
                any(Instant.class));
        verify(restartedRegistry, times(8)).publish(any(SubscriptionTopic.class), replayedPayload.capture(),
                any(Instant.class));
        ObjectMapper objectMapper = new ObjectMapper();
        List<String> firstJson = firstPayload.getAllValues().stream()
                .map(objectMapper::writeValueAsString)
                .toList();
        List<String> replayedJson = replayedPayload.getAllValues().stream()
                .map(objectMapper::writeValueAsString)
                .toList();
        assertThat(firstJson).allMatch(payload -> payload.contains("\"eventId\""));
        assertThat(replayedJson).isEqualTo(firstJson);
    }

    @Test
    void auditsBeforeFanoutAndAuditFailureLeavesRecordUnprocessed() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        CoreEventAuditRepository audit = mock(CoreEventAuditRepository.class);
        WebSocketProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        ConsumerRecord<String, byte[]> record = record(ProductLine.LINEAR_PERPETUAL, 7, true, 12);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties, audit);

        consumer.onCoreEvent(record);

        InOrder inOrder = org.mockito.Mockito.inOrder(audit, registry);
        inOrder.verify(audit).record(eq(ProductLine.LINEAR_PERPETUAL), any(CoreExportEvent.class),
                any(byte[].class), eq(1_700_000_000_000L));
        inOrder.verify(registry, times(8)).publish(any(SubscriptionTopic.class), any(), any(Instant.class));

        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(
                any(ProductLine.class), any(CoreExportEvent.class), any(byte[].class), anyLong());
        SubscriptionRegistry failedRegistry = mock(SubscriptionRegistry.class);
        CoreEventFanoutConsumer failedConsumer = new CoreEventFanoutConsumer(failedRegistry, properties, audit);
        assertThatThrownBy(() -> failedConsumer.onCoreEvent(record)).isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verifyNoInteractions(failedRegistry);
    }

    @Test
    void rejectsKafkaOffsetGapButReconstructedConsumerAcceptsCommittedStartingOffset() {
        SubscriptionRegistry registry = mock(SubscriptionRegistry.class);
        WebSocketProperties properties = properties(ProductLine.SPOT);
        CoreEventFanoutConsumer consumer = new CoreEventFanoutConsumer(registry, properties, auditRepository());

        consumer.onCoreEvent(record(ProductLine.SPOT, 1, false, 12));
        assertThatThrownBy(() -> consumer.onCoreEvent(record(ProductLine.SPOT, 2, false, 14)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka Core event offsets");

        new CoreEventFanoutConsumer(mock(SubscriptionRegistry.class), properties, auditRepository())
                .onCoreEvent(record(ProductLine.SPOT, 2, false, 14));
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

    private static WebSocketProperties properties(ProductLine productLine) {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setGroupId("p8-node");
        return properties;
    }

    private static CoreEventAuditRepository auditRepository() {
        return mock(CoreEventAuditRepository.class);
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
        CoreExportEvent event = new CoreExportEvent(sequence, sequence, 9, commandId,
                CoreMessageType.PLACE_ORDER, ResponseStatus.APPLIED, CoreResultCode.NONE, 101, new byte[] {1},
                List.of(takerUser, makerUser), List.of(taker, maker),
                List.of(new CoreExecutionView(11, 22, 101, 202, 100, 10)));
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, commandId,
                productLine, CommandSource.GATEWAY, 1, sequence, 101, 1_700_000_000_000L, 1)
                .exportEvent(sequence), CoreExportCodec.encodeEvent(event));
        String topic = "surprising." + productLine.topicSegment() + ".core.events.v1";
        return new ConsumerRecord<>(topic, 0, kafkaOffset, productLine.name() + ":" + sequence,
                CoreMessageCodec.encode(message));
    }
}
