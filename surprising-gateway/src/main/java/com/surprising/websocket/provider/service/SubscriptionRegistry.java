package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.session.ClientConnection;

import com.surprising.websocket.provider.model.ChannelMetric;
import com.surprising.websocket.provider.model.DepthUpdate;
import com.surprising.websocket.provider.model.WebSocketAdminMetrics;

import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.config.WebSocketProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
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

    public interface RouteLifecycle {
        void subscribed(SubscriptionTopic topic);
        void unsubscribed(SubscriptionTopic topic);
    }
    private volatile RouteLifecycle routeLifecycle;
    public void routeLifecycle(RouteLifecycle listener) {this.routeLifecycle=listener;}
    private final ObjectMapper objectMapper;
    private final WebSocketProperties properties;
    private final Map<String, ClientConnection> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<SubscriptionTopic>> sessionTopics = new ConcurrentHashMap<>();
    private final Map<SubscriptionTopic, Set<ClientConnection>> subscribers = new ConcurrentHashMap<>();
    private final Map<Long, Set<ClientConnection>> userConnections = new ConcurrentHashMap<>();
    // Last book accepted by each connection queue; immutable Core views are shared, never copied.
    private final Map<SubscriptionTopic, Map<String, CoreOrderBookView>> depthBaselines = new ConcurrentHashMap<>();
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
        connection.onClosed(() -> remove(connection));
    }

    public ClientConnection connection(String sessionId) {
        ClientConnection connection = sessions.get(sessionId);
        if (connection == null) {
            throw new IllegalStateException("websocket session is not registered: " + sessionId);
        }
        return connection;
    }

    /** Connection changes are serialized per socket; Redis lifecycle callbacks never hold this monitor. */
    public void remove(String sessionId) {
        ClientConnection connection = sessions.get(sessionId);
        if (connection != null) remove(connection);
    }

    private void remove(ClientConnection connection) {
        List<SubscriptionTopic> removed;
        synchronized (connection) {
            sessions.remove(connection.id(), connection);
            Set<SubscriptionTopic> topics = sessionTopics.remove(connection.id());
            removed = topics == null ? List.of() : List.copyOf(topics);
            for (SubscriptionTopic topic : removed) removeSubscription(connection, topic);
        }
        connection.close();
        RouteLifecycle lifecycle = routeLifecycle;
        if (lifecycle != null) for (SubscriptionTopic topic : removed) lifecycle.unsubscribed(topic);
    }

    /** Fanout only detaches recipients; its socket watcher performs external lease cleanup. */
    private void disconnect(ClientConnection connection) {
        if (sessions.remove(connection.id(), connection)) {
            Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
            if (topics != null) for (SubscriptionTopic topic : topics) removeSubscription(connection, topic);
        }
        connection.close();
    }

    public void subscribe(ClientConnection connection, SubscriptionTopic topic) {
        synchronized (connection) {
            if (sessions.get(connection.id()) != connection)
                throw new IllegalStateException("websocket session is not registered");
            Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
            if (topics.contains(topic)) return;
            if (topics.size() >= properties.getSession().getMaxSubscriptions())
                throw new IllegalStateException("maximum websocket subscriptions exceeded");
            // Install the local recipient first: a synchronous first Core baseline must not be lost.
            topics.add(topic);
            subscribers.computeIfAbsent(topic, ignored -> ConcurrentHashMap.newKeySet()).add(connection);
            if (topic.userId() != null) {
                userConnections.computeIfAbsent(topic.userId(), ignored -> ConcurrentHashMap.newKeySet()).add(connection);
            }
            RouteLifecycle lifecycle = routeLifecycle;
            try {
                if (lifecycle != null) lifecycle.subscribed(topic);
            } catch (RuntimeException failure) {
                topics.remove(topic);
                removeSubscription(connection, topic);
                throw failure;
            }
        }
    }

    public void unsubscribe(ClientConnection connection, SubscriptionTopic topic) {
        boolean removed;
        synchronized (connection) {
            Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
            removed = topics != null && topics.remove(topic);
            if (removed) removeSubscription(connection, topic);
        }
        RouteLifecycle lifecycle = routeLifecycle;
        if (removed && lifecycle != null) lifecycle.unsubscribed(topic);
    }

    private void removeSubscription(ClientConnection connection, SubscriptionTopic topic) {
        removeDepthBaseline(topic, connection.id());
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections != null) {
            connections.remove(connection);
            if (connections.isEmpty()) subscribers.remove(topic, connections);
        }
        Long userId = topic.userId();
        if (userId != null) {
            Set<SubscriptionTopic> remaining = sessionTopics.get(connection.id());
            boolean stillSubscribedForUser = remaining != null
                    && remaining.stream().anyMatch(t -> userId.equals(t.userId()));
            if (!stillSubscribedForUser) {
                Set<ClientConnection> indexed = userConnections.get(userId);
                if (indexed != null) {
                    indexed.remove(connection);
                    if (indexed.isEmpty()) userConnections.remove(userId, indexed);
                }
            }
        }
    }

    public List<ClientConnection> connections() {
        return List.copyOf(sessions.values());
    }

    /** Failure notification is local only: no Redis calls on the receiver/conductor thread. */
    public void invalidateRealtime() {
        depthBaselines.clear();
        for (ClientConnection connection : connections())
            connection.close(com.surprising.websocket.provider.session.WsCloseStatus.SERVICE_RESTARTED);
    }

    /** Lease cleanup runs on the recovery task after clients have already been closed. */
    public void resetRealtime() {
        invalidateRealtime();
        for (ClientConnection connection : connections()) remove(connection.id());
    }

    public void publish(SubscriptionTopic topic, Object payload, Instant eventTime) {
        fanoutBatches.increment();
        fanoutMessages.increment();
        if (!hasSubscribers(topic)) return;
        String encoded = encode(WsServerMessage.event(topic, payload, eventTime));
        sendEncoded(topic, encoded, null);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.instrumentId())) {
            sendEncoded(topic.withInstrumentId(SubscriptionTopic.WILDCARD), encoded, null);
        }
    }

    private void removeDepthBaseline(SubscriptionTopic topic, String sessionId) {
        var baselines = depthBaselines.get(topic);
        if (baselines == null) return;
        baselines.remove(sessionId);
        if (baselines.isEmpty()) depthBaselines.remove(topic);
    }

    public void publishDepth(SubscriptionTopic topic,
            CoreOrderBookView book,
            String version, String entityId, Instant eventTime) {
        if (topic.channel() != WsChannel.DEPTH || topic.productLine() == null)
            throw new IllegalArgumentException("depth requires an explicit product line");
        var connections = subscribers.get(topic);
        if (connections == null || connections.isEmpty()) return;
        fanoutBatches.increment();
        fanoutMessages.increment();
        var baselines = depthBaselines.computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>());
        List<ClientConnection> failed = null;
        synchronized (baselines) {
            var encodedByBaseline = new java.util.IdentityHashMap<CoreOrderBookView, String>();
            for (ClientConnection connection : connections) {
                var previous = baselines.get(connection.id());
                if (previous != null && book.exportSequence() <= previous.exportSequence()) continue;
                String message = encodedByBaseline.get(previous);
                if (message == null) {
                    var update = DepthUpdate.between(previous, book);
                    message = encode(WsServerMessage.event(topic,
                            new RealtimeWebSocketBridge.VersionedEvent(version, entityId, update), eventTime));
                    encodedByBaseline.put(previous, message);
                }
                if (connection.send(message)) baselines.put(connection.id(), book);
                else {
                    backpressureRejections.increment();
                    if (failed == null) failed = new ArrayList<>();
                    failed.add(connection);
                }
            }
        }
        // Disconnect and lease cleanup may perform external I/O; never under the topic baseline lock.
        if (failed != null) for (ClientConnection connection : failed) disconnect(connection);
    }

    /** 将同一批次的事件一次性投递到每个连接的有界环形队列。 */
    public void publishBatch(SubscriptionTopic topic, List<?> payloads, Instant eventTime) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        fanoutBatches.increment();
        fanoutMessages.add(payloads.size());
        if (!hasSubscribers(topic)) return;
        List<String> messages = new ArrayList<>(payloads.size());
        for (Object payload : payloads) {
            messages.add(encode(WsServerMessage.event(topic, payload, eventTime)));
        }
        fanout(topic, messages);
    }

    public void publishUserSnapshot(com.surprising.product.api.ProductLine product, long userId, Object payload) {
        Set<ClientConnection> candidates = userConnections.get(userId);
        if (candidates == null || candidates.isEmpty()) return;
        String encoded = encode(new WsServerMessage("snapshot", null, null, null, null,
                userId, product, payload, null, Instant.now()));
        for (ClientConnection connection : candidates) {
            Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
            if (topics == null) continue;
            boolean subscribed = false;
            for (SubscriptionTopic topic : topics) {
                if (topic.productLine() == product && topic.userId() != null && topic.userId() == userId) {
                    subscribed = true;
                    break;
                }
            }
            if (subscribed && !connection.send(encoded)) {
                disconnect(connection);
            }
        }
    }

    public List<SubscriptionTopic> topics(WsChannel channel) {
        List<SubscriptionTopic> result = new ArrayList<>();
        for (SubscriptionTopic topic : subscribers.keySet()) {
            if (topic.channel() == channel) result.add(topic);
        }
        return List.copyOf(result);
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

    public int queuedMessageCount() {
        return sessions.values().stream()
                .mapToInt(ClientConnection::queuedMessages)
                .sum();
    }

    public int queueCapacity() {
        return sessions.values().stream()
                .mapToInt(ClientConnection::queueCapacity)
                .sum();
    }

    public long backpressureRejectionCount() {
        return backpressureRejections.sum();
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
        Gauge.builder("surprising.websocket.queue.depth", this, SubscriptionRegistry::queuedMessageCount)
                .description("Queued WebSocket messages across active sessions")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.queue.capacity", this, SubscriptionRegistry::queueCapacity)
                .description("Bounded WebSocket queue capacity across active sessions")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.fanout.batches", fanoutBatches, LongAdder::sum)
                .description("WebSocket 批量 fanout 次数")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.fanout.messages", fanoutMessages, LongAdder::sum)
                .description("WebSocket fanout 消息数")
                .register(meterRegistry);
        Gauge.builder("surprising.websocket.backpressure.rejections", backpressureRejections, LongAdder::sum)
                .description("WebSocket 背压导致的连接拒绝次数")
                .register(meterRegistry);
    }

    private String encode(WsServerMessage message) {
        return objectMapper.writeValueAsString(message);
    }

    private boolean hasSubscribers(SubscriptionTopic topic) {
        if (subscriberCount(topic) > 0) return true;
        return !topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.instrumentId())
                && subscriberCount(topic.withInstrumentId(SubscriptionTopic.WILDCARD)) > 0;
    }

    private void fanout(SubscriptionTopic topic, List<String> messages) {
        sendEncoded(topic, null, messages);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.instrumentId())) {
            sendEncoded(topic.withInstrumentId(SubscriptionTopic.WILDCARD), null, messages);
        }
    }

    private void sendEncoded(SubscriptionTopic subscription, String singleMessage, List<String> messages) {
        Set<ClientConnection> connections = subscribers.get(subscription);
        if (connections == null || connections.isEmpty()) return;
        List<io.netty.buffer.ByteBuf> frames = null;
        List<String> batchView = null;
        try {
            for (ClientConnection connection : connections) {
                boolean accepted;
                if (connection.supportsZeroCopyFrames()) {
                    if (frames == null) {
                        if (singleMessage != null) {
                            frames = List.of(io.netty.buffer.ByteBufUtil.writeUtf8(
                                    io.netty.buffer.ByteBufAllocator.DEFAULT, singleMessage));
                            batchView = List.of(singleMessage);
                        } else {
                            frames = new ArrayList<>(messages.size());
                            for (String msg : messages) {
                                frames.add(io.netty.buffer.ByteBufUtil.writeUtf8(
                                        io.netty.buffer.ByteBufAllocator.DEFAULT, msg));
                            }
                            batchView = messages;
                        }
                    }
                    accepted = connection.sendFrames(frames, batchView);
                } else if (singleMessage != null) {
                    accepted = connection.send(singleMessage);
                } else {
                    accepted = messages.size() == 1
                            ? connection.send(messages.getFirst()) : connection.sendBatch(messages);
                }
                if (!accepted) {
                    backpressureRejections.increment();
                    disconnect(connection);
                }
            }
        } finally {
            if (frames != null) {
                for (io.netty.buffer.ByteBuf frame : frames) {
                    frame.release();
                }
            }
        }
    }

    public SubscriptionTopic publicTopic(WsChannel channel, String instrumentId) {
        return new SubscriptionTopic(channel, instrumentId, null, null);
    }

    private static final class ChannelAccumulator {
        private int topicCount;
        private int subscriberCount;
    }


    public WebSocketAdminMetrics metrics(String adminUserId, String adminUsername) {
        return new WebSocketAdminMetrics(java.time.Instant.now(), adminUserId, adminUsername,
                activeConnectionCount(), authenticatedConnectionCount(), anonymousConnectionCount(),
                totalSubscriptionCount(), uniqueTopicCount(), maxSubscriptionsPerSession(), channelMetrics());
    }
}
