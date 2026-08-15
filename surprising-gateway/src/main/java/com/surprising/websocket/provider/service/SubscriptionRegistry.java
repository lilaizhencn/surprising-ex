package com.surprising.websocket.provider.service;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class SubscriptionRegistry {

    private final ObjectMapper objectMapper;
    private final WebSocketProperties properties;
    private final Map<String, ClientConnection> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<SubscriptionTopic>> sessionTopics = new ConcurrentHashMap<>();
    private final Map<SubscriptionTopic, Set<ClientConnection>> subscribers = new ConcurrentHashMap<>();
    private final LongAdder fanoutBatches = new LongAdder();
    private final LongAdder fanoutMessages = new LongAdder();
    private final LongAdder backpressureRejections = new LongAdder();

    @Autowired
    public SubscriptionRegistry(ObjectMapper objectMapper, WebSocketProperties properties, MeterRegistry meterRegistry) {
        this(objectMapper, properties);
        registerMeters(meterRegistry);
    }

    public SubscriptionRegistry(ObjectMapper objectMapper, WebSocketProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void add(ClientConnection connection) {
        sessions.put(connection.id(), connection);
        sessionTopics.put(connection.id(), ConcurrentHashMap.newKeySet());
    }

    public ClientConnection connection(String sessionId) {
        ClientConnection connection = sessions.get(sessionId);
        if (connection == null) {
            throw new IllegalStateException("websocket session is not registered: " + sessionId);
        }
        return connection;
    }

    public void remove(String sessionId) {
        ClientConnection connection = sessions.remove(sessionId);
        Set<SubscriptionTopic> topics = sessionTopics.remove(sessionId);
        if (topics != null && connection != null) {
            for (SubscriptionTopic topic : topics) {
                Set<ClientConnection> connections = subscribers.get(topic);
                if (connections != null) {
                    connections.remove(connection);
                    if (connections.isEmpty()) {
                        subscribers.remove(topic, connections);
                    }
                }
            }
        }
        if (connection != null) {
            connection.close();
        }
    }

    public void subscribe(ClientConnection connection, SubscriptionTopic topic) {
        Set<SubscriptionTopic> topics = sessionTopics.computeIfAbsent(connection.id(), key -> ConcurrentHashMap.newKeySet());
        if (topics.size() >= properties.getSession().getMaxSubscriptions() && !topics.contains(topic)) {
            throw new IllegalStateException("maximum websocket subscriptions exceeded");
        }
        topics.add(topic);
        subscribers.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    public void unsubscribe(ClientConnection connection, SubscriptionTopic topic) {
        Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
        if (topics != null) {
            topics.remove(topic);
        }
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections != null) {
            connections.remove(connection);
            if (connections.isEmpty()) {
                subscribers.remove(topic, connections);
            }
        }
    }

    public void publish(SubscriptionTopic topic, Object payload, Instant eventTime) {
        publishBatch(topic, List.of(payload), eventTime);
    }

    /** 将同一 Kafka 拉取批次的事件一次性投递到每个连接的有界环形队列。 */
    public void publishBatch(SubscriptionTopic topic, List<?> payloads, Instant eventTime) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        fanoutBatches.increment();
        fanoutMessages.add(payloads.size());
        sendBatch(topic, payloads, eventTime);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.symbol())) {
            sendBatch(topic.withSymbol(SubscriptionTopic.WILDCARD), payloads, eventTime);
        }
    }

    /** 按事件自身时间批量编码，避免同一 Kafka 批次被拆成逐条网络写入。 */
    public void publishTimedBatch(SubscriptionTopic topic, List<TimedPayload> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        fanoutBatches.increment();
        fanoutMessages.add(events.size());
        sendTimedBatch(topic, events);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.symbol())) {
            sendTimedBatch(topic.withSymbol(SubscriptionTopic.WILDCARD), events);
        }
    }

    public int subscriberCount(SubscriptionTopic topic) {
        return subscribers.getOrDefault(topic, Set.of()).size();
    }

    public int activeConnectionCount() {
        return sessions.size();
    }

    public long authenticatedConnectionCount() {
        return sessions.values().stream()
                .filter(connection -> connection.authenticatedUserId() != null)
                .count();
    }

    public long anonymousConnectionCount() {
        return activeConnectionCount() - authenticatedConnectionCount();
    }

    public long totalSubscriptionCount() {
        return sessionTopics.values().stream()
                .mapToLong(Set::size)
                .sum();
    }

    public int uniqueTopicCount() {
        return subscribers.size();
    }

    public int maxSubscriptionsPerSession() {
        return sessionTopics.values().stream()
                .mapToInt(Set::size)
                .max()
                .orElse(0);
    }

    public List<ChannelMetric> channelMetrics() {
        Map<WsChannel, ChannelAccumulator> metrics = new EnumMap<>(WsChannel.class);
        subscribers.forEach((topic, connections) -> {
            ChannelAccumulator accumulator = metrics.computeIfAbsent(topic.channel(), ignored -> new ChannelAccumulator());
            accumulator.topicCount++;
            accumulator.subscriberCount += connections.size();
        });
        List<ChannelMetric> rows = new ArrayList<>();
        metrics.forEach((channel, accumulator) -> rows.add(new ChannelMetric(
                channel.name(), accumulator.topicCount, accumulator.subscriberCount)));
        rows.sort((left, right) -> left.channel().compareTo(right.channel()));
        return rows;
    }

    private void registerMeters(MeterRegistry meterRegistry) {
        Gauge.builder("surprising.websocket.connections.active", this, SubscriptionRegistry::activeConnectionCount)
                .description("Active WebSocket client connections on this node")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.connections.authenticated", this, SubscriptionRegistry::authenticatedConnectionCount)
                .description("Authenticated WebSocket client connections on this node")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.subscriptions.active", this, SubscriptionRegistry::totalSubscriptionCount)
                .description("Active WebSocket subscriptions on this node")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.topics.active", this, SubscriptionRegistry::uniqueTopicCount)
                .description("Unique subscribed WebSocket topics on this node")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.fanout.batches", fanoutBatches, LongAdder::sum)
                .description("WebSocket Kafka 批量 fanout 次数")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.fanout.messages", fanoutMessages, LongAdder::sum)
                .description("WebSocket fanout 消息数")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.backpressure.rejections", backpressureRejections, LongAdder::sum)
                .description("WebSocket 背压导致的连接拒绝次数")
                .register(meterRegistry);
    }

    private void sendBatch(SubscriptionTopic topic, List<?> payloads, Instant eventTime) {
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        List<String> messages = payloads.stream()
                .map(payload -> objectMapper.writeValueAsString(WsServerMessage.event(topic, payload, eventTime)))
                .toList();
        for (ClientConnection connection : connections) {
            boolean accepted = messages.size() == 1
                    ? connection.send(messages.getFirst())
                    : connection.sendBatch(messages);
            if (!accepted) {
                backpressureRejections.increment();
                remove(connection.id());
            }
        }
    }

    private void sendTimedBatch(SubscriptionTopic topic, List<TimedPayload> events) {
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections == null || connections.isEmpty()) {
            return;
        }
        List<String> messages = events.stream()
                .map(event -> objectMapper.writeValueAsString(
                        WsServerMessage.event(topic, event.payload(), event.eventTime())))
                .toList();
        for (ClientConnection connection : connections) {
            if (!connection.sendBatch(messages)) {
                backpressureRejections.increment();
                remove(connection.id());
            }
        }
    }

    public SubscriptionTopic publicTopic(WsChannel channel, String symbol) {
        return new SubscriptionTopic(channel, symbol, null, null);
    }

    private static final class ChannelAccumulator {
        private int topicCount;
        private int subscriberCount;
    }

    public record ChannelMetric(
            String channel,
            int topicCount,
            int subscriberCount) {
    }

    public record TimedPayload(Object payload, Instant eventTime) {
    }
}
