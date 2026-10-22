package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.session.ClientConnection;

import com.surprising.websocket.provider.model.DepthUpdate;
import com.surprising.websocket.provider.model.WebSocketAdminMetrics;
import com.surprising.websocket.provider.model.ChannelMetric;
import com.surprising.websocket.provider.model.TimedPayload;

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
    // Last book accepted by each connection queue; immutable Core views are shared, never copied.
    // Owned by this registry under its monitor and removed on unsubscribe/disconnect.
    private final Map<SubscriptionTopic, Map<String, CoreOrderBookView>> depthBaselines = new HashMap<>();
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
        synchronized (connection) {
            List<SubscriptionTopic> removed;
            synchronized (this) {
                sessions.remove(connection.id(), connection);
                Set<SubscriptionTopic> topics = sessionTopics.remove(connection.id());
                removed = topics == null ? List.of() : List.copyOf(topics);
                for (SubscriptionTopic topic : removed) removeSubscription(connection, topic);
            }
            connection.close();
            RouteLifecycle lifecycle = routeLifecycle;
            if (lifecycle != null) for (SubscriptionTopic topic : removed) lifecycle.unsubscribed(topic);
        }
    }

    /** Fanout only detaches recipients; its socket watcher performs external lease cleanup. */
    private void disconnect(ClientConnection connection) {
        synchronized (this) {
            if (sessions.remove(connection.id(), connection)) {
                Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
                if (topics != null) for (SubscriptionTopic topic : topics) removeSubscription(connection, topic);
            }
        }
        connection.close();
    }

    public void subscribe(ClientConnection connection, SubscriptionTopic topic) {
        synchronized (connection) {
            RouteLifecycle lifecycle;
            synchronized (this) {
                if (sessions.get(connection.id()) != connection)
                    throw new IllegalStateException("websocket session is not registered");
                Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
                if (topics.contains(topic)) return;
                if (topics.size() >= properties.getSession().getMaxSubscriptions())
                    throw new IllegalStateException("maximum websocket subscriptions exceeded");
                // Install the local recipient first: a synchronous first Core baseline must not be lost.
                topics.add(topic);
                subscribers.computeIfAbsent(topic, ignored -> ConcurrentHashMap.newKeySet()).add(connection);
                lifecycle = routeLifecycle;
            }
            try {
                if (lifecycle != null) lifecycle.subscribed(topic);
            } catch (RuntimeException failure) {
                synchronized (this) {
                    var topics = sessionTopics.get(connection.id());
                    if (topics != null) topics.remove(topic);
                    removeSubscription(connection, topic);
                }
                throw failure;
            }
        }
    }

    public void unsubscribe(ClientConnection connection, SubscriptionTopic topic) {
        synchronized (connection) {
            boolean removed;
            synchronized (this) {
                Set<SubscriptionTopic> topics = sessionTopics.get(connection.id());
                removed = topics != null && topics.remove(topic);
                if (removed) removeSubscription(connection, topic);
            }
            RouteLifecycle lifecycle = routeLifecycle;
            if (removed && lifecycle != null) lifecycle.unsubscribed(topic);
        }
    }

    private void removeSubscription(ClientConnection connection, SubscriptionTopic topic) {
        removeDepthBaseline(topic, connection.id());
        Set<ClientConnection> connections = subscribers.get(topic);
        if (connections != null) {
            connections.remove(connection);
            if (connections.isEmpty()) subscribers.remove(topic, connections);
        }
    }

    public List<ClientConnection> connections() {
        return List.copyOf(sessions.values());
    }

    /** Failure notification is local only: no Redis calls on the receiver/conductor thread. */
    public void invalidateRealtime() {
        synchronized (this) { depthBaselines.clear(); }
        for (ClientConnection connection : connections())
            connection.close(org.springframework.web.socket.CloseStatus.SERVICE_RESTARTED);
    }

    /** Lease cleanup runs on the recovery task after clients have already been closed. */
    public void resetRealtime() {
        invalidateRealtime();
        for (ClientConnection connection : connections()) remove(connection.id());
    }

    public void publish(SubscriptionTopic topic, Object payload, Instant eventTime) {
        publishBatch(topic, List.of(payload), eventTime);
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
        List<ClientConnection> failed = null;
        synchronized (this) {
            if (topic.channel() != WsChannel.DEPTH || topic.productLine() == null)
                throw new IllegalArgumentException("depth requires an explicit product line");
            var connections = subscribers.get(topic);
            if (connections == null || connections.isEmpty()) return;
            fanoutBatches.increment();
            fanoutMessages.increment();
            var baselines = depthBaselines.computeIfAbsent(topic, ignored -> new HashMap<>());
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
        // Disconnect and lease cleanup may perform external I/O; never under the global book lock.
        if (failed != null) for (ClientConnection connection : failed) disconnect(connection);
    }

    /** 将同一 Kafka 拉取批次的事件一次性投递到每个连接的有界环形队列。 */
    public void publishBatch(SubscriptionTopic topic, List<?> payloads, Instant eventTime) {
        if (payloads == null || payloads.isEmpty()) {
            return;
        }
        fanoutBatches.increment();
        fanoutMessages.add(payloads.size());
        if (!hasSubscribers(topic)) return;
        var messages = payloads.stream().map(payload -> encode(WsServerMessage.event(topic, payload, eventTime))).toList();
        fanout(topic, messages);
    }

    /** 按事件自身时间批量编码，避免同一 Kafka 批次被拆成逐条网络写入。 */
    public void publishTimedBatch(SubscriptionTopic topic, List<TimedPayload> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        fanoutBatches.increment();
        fanoutMessages.add(events.size());
        if (!hasSubscribers(topic)) return;
        var messages = events.stream().map(event -> encode(WsServerMessage.event(
                topic, event.payload(), event.eventTime()))).toList();
        fanout(topic, messages);
    }

    public void publishUserSnapshot(com.surprising.product.api.ProductLine product,long userId,Object payload) {
        var encoded=encode(new WsServerMessage("snapshot",null,null,null,null,
                userId,product,payload,null,Instant.now()));
        sessionTopics.forEach((sessionId,topics)->{
            boolean subscribed=topics.stream().anyMatch(t->t.productLine()==product && t.userId()!=null && t.userId()==userId);
            ClientConnection connection=sessions.get(sessionId);
            if(subscribed && connection!=null && !connection.send(encoded))disconnect(connection);
        });
    }

    public List<SubscriptionTopic> topics(WsChannel channel) {
        return subscribers.keySet().stream().filter(topic -> topic.channel() == channel).toList();
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
                .description("WebSocket Kafka 批量 fanout 次数")
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
        sendEncodedBatch(topic, messages);
        if (!topic.channel().isPublicChannel() && !SubscriptionTopic.WILDCARD.equals(topic.instrumentId())) {
            sendEncodedBatch(topic.withInstrumentId(SubscriptionTopic.WILDCARD), messages);
        }
    }

    private void sendEncodedBatch(SubscriptionTopic subscription, List<String> messages) {
        Set<ClientConnection> connections = subscribers.get(subscription);
        if (connections == null || connections.isEmpty()) return;
        for (ClientConnection connection : connections) {
            boolean accepted = messages.size() == 1
                    ? connection.send(messages.getFirst()) : connection.sendBatch(messages);
            if (!accepted) {
                backpressureRejections.increment();
                disconnect(connection);
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
