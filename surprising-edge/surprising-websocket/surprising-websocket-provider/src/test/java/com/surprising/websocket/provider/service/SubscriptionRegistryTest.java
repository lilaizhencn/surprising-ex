package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void privateSymbolFanoutDoesNotLeakAcrossUsersAndAlsoReachesSameUserWildcard() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection user1001Symbol = connection("s-1001-symbol");
        ClientConnection user1001Wildcard = connection("s-1001-all");
        ClientConnection user2002SameSymbol = connection("s-2002-symbol");
        registry.add(user1001Symbol);
        registry.add(user1001Wildcard);
        registry.add(user2002SameSymbol);
        registry.subscribe(user1001Symbol,
                new SubscriptionTopic(WsChannel.ORDERS, "BTC-USDT", null, 1001L));
        registry.subscribe(user1001Wildcard,
                new SubscriptionTopic(WsChannel.ORDERS, SubscriptionTopic.WILDCARD, null, 1001L));
        registry.subscribe(user2002SameSymbol,
                new SubscriptionTopic(WsChannel.ORDERS, "BTC-USDT", null, 2002L));

        OrderEvent event = new OrderEvent(1L, 11L, 1001L, "BTC-USDT",
                OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, null,
                Instant.parse("2026-07-01T00:00:00Z"), "trace-private-1");
        registry.publish(new SubscriptionTopic(WsChannel.ORDERS, "BTC-USDT", null, 1001L),
                event, event.eventTime());

        ArgumentCaptor<String> symbolPayload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> wildcardPayload = ArgumentCaptor.forClass(String.class);
        verify(user1001Symbol).send(symbolPayload.capture());
        verify(user1001Wildcard).send(wildcardPayload.capture());
        verify(user2002SameSymbol, never()).send(anyString());
        assertThat(symbolPayload.getValue()).contains("\"userId\":1001", "\"symbol\":\"BTC-USDT\"");
        assertThat(wildcardPayload.getValue()).contains("\"userId\":1001", "\"symbol\":\"*\"");
    }

    @Test
    void failedSubscriberIsRemovedWithoutDroppingOtherSubscribers() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection failed = connection("s-failed");
        ClientConnection healthy = connection("s-healthy");
        when(failed.send(anyString())).thenReturn(false);
        registry.add(failed);
        registry.add(healthy);
        SubscriptionTopic topic = new SubscriptionTopic(WsChannel.POSITIONS, "ETH-USDT", null, 3003L);
        registry.subscribe(failed, topic);
        registry.subscribe(healthy, topic);

        registry.publish(topic, "payload", Instant.parse("2026-07-01T00:00:00Z"));

        verify(failed).send(anyString());
        verify(failed).close();
        verify(healthy).send(anyString());
        assertThat(registry.subscriberCount(topic)).isEqualTo(1);
    }

    @Test
    void legacyTopicPublishesToProductLineSubscribersDuringMigration() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection productSubscriber = connection("s-product");
        registry.add(productSubscriber);
        registry.subscribe(productSubscriber,
                new SubscriptionTopic(WsChannel.TRADES, "BTC-USDT", null, null, ProductLine.LINEAR_DELIVERY));

        registry.publish(new SubscriptionTopic(WsChannel.TRADES, "BTC-USDT", null, null),
                "payload", Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(productSubscriber).send(payload.capture());
        assertThat(payload.getValue()).contains("\"productLine\":\"LINEAR_DELIVERY\"");
    }

    @Test
    void reportsConnectionSubscriptionAndChannelMetrics() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection anonymous = connection("s-anon", null);
        ClientConnection user = connection("s-user", 7001L);
        registry.add(anonymous);
        registry.add(user);
        SubscriptionTopic publicTopic = new SubscriptionTopic(WsChannel.TRADES, "BTC-USDT", null, null);
        SubscriptionTopic privateTopic = new SubscriptionTopic(WsChannel.ORDERS, "BTC-USDT", null, 7001L);
        registry.subscribe(anonymous, publicTopic);
        registry.subscribe(user, publicTopic);
        registry.subscribe(user, privateTopic);

        assertThat(registry.activeConnectionCount()).isEqualTo(2);
        assertThat(registry.authenticatedConnectionCount()).isEqualTo(1);
        assertThat(registry.anonymousConnectionCount()).isEqualTo(1);
        assertThat(registry.totalSubscriptionCount()).isEqualTo(3);
        assertThat(registry.uniqueTopicCount()).isEqualTo(2);
        assertThat(registry.maxSubscriptionsPerSession()).isEqualTo(2);
        assertThat(registry.channelMetrics()).extracting(SubscriptionRegistry.ChannelMetric::channel)
                .containsExactly("ORDERS", "TRADES");
    }

    private ClientConnection connection(String id) {
        return connection(id, null);
    }

    private ClientConnection connection(String id, Long userId) {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn(id);
        when(connection.authenticatedUserId()).thenReturn(userId);
        when(connection.send(anyString())).thenReturn(true);
        return connection;
    }
}
