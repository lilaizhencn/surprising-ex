package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderEvent;
import com.surprising.trading.api.model.OrderEventType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SubscriptionRegistryTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void wildcardExecutionBatchesKeepInstrumentAndProduct(ProductLine product) {
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var subscriber = connection("execution-all");
        registry.add(subscriber);
        registry.subscribe(subscriber, new SubscriptionTopic(WsChannel.EXECUTION_REPORTS,
                SubscriptionTopic.WILDCARD, null, 1001L, product));

        var topic = new SubscriptionTopic(WsChannel.EXECUTION_REPORTS, "1", null, 1001L, product);
        var time = Instant.parse("2026-09-26T00:00:00Z");
        registry.publishTimedBatch(topic, java.util.List.of(new SubscriptionRegistry.TimedPayload("fill", time)));
        ArgumentCaptor<ClientConnection.TracedMessage> messages = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(subscriber).send(messages.capture());
        var event = new ObjectMapper().readTree(messages.getValue().payload());
        assertThat(event.path("traceId").asText()).isEqualTo(messages.getValue().traceId());
        assertThat(event.path("instrumentId").asText()).isEqualTo("1");
        assertThat(event.path("productLine").asText()).isEqualTo(product.name());
        assertThat(event.path("userId").asLong()).isEqualTo(1001);
    }

    @Test
    void timedBatchRetainsEachMessageRootAcrossFanoutAndAmbientContext() {
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var subscriber = connection("traced-batch");
        var topic = new SubscriptionTopic(WsChannel.TRADES, "1", null, null, ProductLine.LINEAR_PERPETUAL);
        registry.add(subscriber);
        registry.subscribe(subscriber, topic);
        when(subscriber.sendTracedBatch(org.mockito.ArgumentMatchers.anyList())).thenReturn(true);
        try (var scope = com.surprising.trading.api.TraceContext.open("ambient")) {
            registry.publishTimedBatch(topic, java.util.List.of(
                    new SubscriptionRegistry.TimedPayload("first", Instant.now(), "root-1"),
                    new SubscriptionRegistry.TimedPayload("second", Instant.now(), "root-2")));
            assertThat(com.surprising.trading.api.TraceContext.current()).isEqualTo("ambient");
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ClientConnection.TracedMessage>> batch = ArgumentCaptor.forClass(java.util.List.class);
        verify(subscriber).sendTracedBatch(batch.capture());
        assertThat(batch.getValue()).extracting(ClientConnection.TracedMessage::traceId).containsExactly("root-1", "root-2");
        for (var message : batch.getValue()) {
            assertThat(new ObjectMapper().readTree(message.payload()).path("traceId").asText()).isEqualTo(message.traceId());
        }
        assertThat(com.surprising.trading.api.TraceContext.current()).isNull();
    }

    @Test
    void lifecycleIsBalancedAcrossDuplicatesDisconnectsAndFailedRegistration() {
        var registry=new SubscriptionRegistry(new ObjectMapper(),new WebSocketProperties());
        var lifecycle=mock(SubscriptionRegistry.RouteLifecycle.class);registry.routeLifecycle(lifecycle);
        var connection=connection("node-membership");registry.add(connection);
        var topic=new SubscriptionTopic(WsChannel.ORDERS,"1",null,1001L,ProductLine.SPOT);
        registry.subscribe(connection,topic);registry.subscribe(connection,topic);
        verify(lifecycle,org.mockito.Mockito.times(1)).subscribed(topic);
        registry.remove(connection.id());verify(lifecycle,org.mockito.Mockito.times(1)).unsubscribed(topic);
        assertThat(registry.subscriberCount(topic)).isZero();
        var failed=connection("registration-failed");registry.add(failed);
        org.mockito.Mockito.doThrow(new IllegalStateException("Valkey unavailable")).when(lifecycle).subscribed(topic);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->registry.subscribe(failed,topic)).isInstanceOf(IllegalStateException.class);
        assertThat(registry.subscriberCount(topic)).isZero();
    }

    @Test
    void privateSymbolFanoutDoesNotLeakAcrossUsersAndAlsoReachesSameUserWildcard() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection user1001Symbol = connection("s-1001-instrumentId");
        ClientConnection user1001Wildcard = connection("s-1001-all");
        ClientConnection user2002SameSymbol = connection("s-2002-instrumentId");
        registry.add(user1001Symbol);
        registry.add(user1001Wildcard);
        registry.add(user2002SameSymbol);
        registry.subscribe(user1001Symbol,
                new SubscriptionTopic(WsChannel.ORDERS, "1", null, 1001L));
        registry.subscribe(user1001Wildcard,
                new SubscriptionTopic(WsChannel.ORDERS, SubscriptionTopic.WILDCARD, null, 1001L));
        registry.subscribe(user2002SameSymbol,
                new SubscriptionTopic(WsChannel.ORDERS, "1", null, 2002L));

        OrderEvent event = new OrderEvent(1L, 11L, 1001L, "1",
                OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, null,
                Instant.parse("2026-07-01T00:00:00Z"), "trace-private-1");
        registry.publish(new SubscriptionTopic(WsChannel.ORDERS, "1", null, 1001L),
                event, event.eventTime());

        ArgumentCaptor<ClientConnection.TracedMessage> symbolPayload = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        ArgumentCaptor<ClientConnection.TracedMessage> wildcardPayload = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(user1001Symbol).send(symbolPayload.capture());
        verify(user1001Wildcard).send(wildcardPayload.capture());
        verify(user2002SameSymbol, never()).send(any(ClientConnection.TracedMessage.class));
        assertThat(symbolPayload.getValue().payload()).contains("\"userId\":1001", "\"instrumentId\":\"1\"");
        assertThat(wildcardPayload.getValue()).isEqualTo(symbolPayload.getValue());
    }

    @Test
    void failedSubscriberIsRemovedWithoutDroppingOtherSubscribers() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection failed = connection("s-failed");
        ClientConnection healthy = connection("s-healthy");
        when(failed.send(any(ClientConnection.TracedMessage.class))).thenReturn(false);
        registry.add(failed);
        registry.add(healthy);
        SubscriptionTopic topic = new SubscriptionTopic(WsChannel.POSITIONS, "2", null, 3003L);
        registry.subscribe(failed, topic);
        registry.subscribe(healthy, topic);

        registry.publish(topic, "payload", Instant.parse("2026-07-01T00:00:00Z"));

        verify(failed).send(any(ClientConnection.TracedMessage.class));
        verify(failed).close();
        verify(healthy).send(any(ClientConnection.TracedMessage.class));
        assertThat(registry.subscriberCount(topic)).isEqualTo(1);
        assertThat(registry.backpressureRejectionCount()).isEqualTo(1);
    }

    @Test
    void productlessTopicDoesNotReachProductSpecificSubscribers() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection productSubscriber = connection("s-product");
        registry.add(productSubscriber);
        registry.subscribe(productSubscriber,
                new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null, ProductLine.LINEAR_DELIVERY));

        registry.publish(new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null),
                "payload", Instant.parse("2026-07-01T00:00:00Z"));

        verify(productSubscriber, never()).send(any(ClientConnection.TracedMessage.class));
    }

    @Test
    void reportsConnectionSubscriptionAndChannelMetrics() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection anonymous = connection("s-anon", null);
        ClientConnection user = connection("s-user", 7001L);
        registry.add(anonymous);
        registry.add(user);
        when(anonymous.queuedMessages()).thenReturn(2);
        when(user.queuedMessages()).thenReturn(3);
        when(anonymous.queueCapacity()).thenReturn(4);
        when(user.queueCapacity()).thenReturn(6);
        SubscriptionTopic publicTopic = new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null);
        SubscriptionTopic privateTopic = new SubscriptionTopic(WsChannel.ORDERS, "1", null, 7001L);
        registry.subscribe(anonymous, publicTopic);
        registry.subscribe(user, publicTopic);
        registry.subscribe(user, privateTopic);

        assertThat(registry.activeConnectionCount()).isEqualTo(2);
        assertThat(registry.authenticatedConnectionCount()).isEqualTo(1);
        assertThat(registry.anonymousConnectionCount()).isEqualTo(1);
        assertThat(registry.totalSubscriptionCount()).isEqualTo(3);
        assertThat(registry.uniqueTopicCount()).isEqualTo(2);
        assertThat(registry.maxSubscriptionsPerSession()).isEqualTo(2);
        assertThat(registry.queuedMessageCount()).isEqualTo(5);
        assertThat(registry.queueCapacity()).isEqualTo(10);
        assertThat(registry.channelMetrics()).extracting(SubscriptionRegistry.ChannelMetric::channel)
                .containsExactly("INDEX_PRICE", "ORDERS");
    }

    @Test
    void depthBaselinesArePerSubscriberAndResetAfterUnsubscribe() {
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var first = connection("first");
        var second = connection("second");
        var otherProduct = connection("other");
        var topic = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, ProductLine.LINEAR_PERPETUAL);
        registry.add(first); registry.add(second); registry.add(otherProduct);
        registry.subscribe(first, topic);
        registry.subscribe(otherProduct, new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, ProductLine.SPOT));
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(1, java.util.List.of()), "v1", "book", Instant.now());
        registry.subscribe(second, topic);
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(2, java.util.List.of()), "v2", "book", Instant.now());
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(1, java.util.List.of()), "v1", "book", Instant.now());
        registry.unsubscribe(first, topic);
        registry.subscribe(first, topic);
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(3, java.util.List.of()), "v3", "book", Instant.now());
        var messages = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(first, org.mockito.Mockito.times(3)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).payload()).contains("\"updateType\":\"SNAPSHOT\"");
        assertThat(messages.getAllValues().get(1).payload()).contains("\"updateType\":\"DELTA\"", "\"previousSequence\":\"1\"");
        assertThat(messages.getAllValues().get(2).payload()).contains("\"updateType\":\"SNAPSHOT\"");
        var joined = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(second, org.mockito.Mockito.times(2)).send(joined.capture());
        assertThat(joined.getAllValues().get(0).payload()).contains("\"updateType\":\"SNAPSHOT\"");
        assertThat(joined.getAllValues().get(1).payload()).contains("\"updateType\":\"DELTA\"", "\"previousSequence\":\"2\"");
        verify(otherProduct, never()).send(any(ClientConnection.TracedMessage.class));
    }

    @Test
    void depthBackpressureRemovesFailedConnectionAndKeepsHealthyBaseline() {
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var failed = connection("failed-depth");
        var healthy = connection("healthy-depth");
        when(failed.send(any(ClientConnection.TracedMessage.class))).thenReturn(false);
        var topic = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, ProductLine.SPOT);
        registry.add(failed); registry.add(healthy);
        registry.subscribe(failed, topic); registry.subscribe(healthy, topic);
        for (int seq = 1; seq <= 2; seq++)
            registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(seq, java.util.List.of()), "v" + seq, "book", Instant.now());
        var messages = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(healthy, org.mockito.Mockito.times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(1).payload()).contains("\"updateType\":\"DELTA\"");
        assertThat(registry.subscriberCount(topic)).isEqualTo(1);
        verify(failed).close();
    }

    @Test
    void depthEncodesOncePerSharedBaselineButKeepsNewSubscriberSnapshotsSeparate() {
        var mapper = org.mockito.Mockito.spy(new ObjectMapper());
        var registry = new SubscriptionRegistry(mapper, new WebSocketProperties());
        var topic = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, ProductLine.SPOT);
        var first = connection("shared-first");
        var second = connection("shared-second");
        var joined = connection("shared-joined");
        for (var client : java.util.List.of(first, second, joined)) registry.add(client);
        registry.subscribe(first, topic); registry.subscribe(second, topic);
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(1, java.util.List.of()),
                "v1", "book", Instant.now());
        verify(mapper, org.mockito.Mockito.times(1)).writeValueAsString(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.clearInvocations(mapper);
        registry.subscribe(joined, topic);
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(2, java.util.List.of()),
                "v2", "book", Instant.now());
        verify(mapper, org.mockito.Mockito.times(2)).writeValueAsString(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.clearInvocations(mapper);
        registry.publishDepth(topic, new com.surprising.aeron.protocol.CoreOrderBookView(3, java.util.List.of()),
                "v3", "book", Instant.now());
        verify(mapper, org.mockito.Mockito.times(1)).writeValueAsString(org.mockito.ArgumentMatchers.any());
        var messages = ArgumentCaptor.forClass(ClientConnection.TracedMessage.class);
        verify(joined, org.mockito.Mockito.times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).payload()).contains("\"updateType\":\"SNAPSHOT\"");
        assertThat(messages.getAllValues().get(1).payload()).contains("\"previousSequence\":\"2\"");
    }

    private ClientConnection connection(String id) {
        return connection(id, null);
    }

    private ClientConnection connection(String id, Long userId) {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn(id);
        when(connection.authenticatedUserId()).thenReturn(userId);
        when(connection.send(any(ClientConnection.TracedMessage.class))).thenReturn(true);
        return connection;
    }
}
