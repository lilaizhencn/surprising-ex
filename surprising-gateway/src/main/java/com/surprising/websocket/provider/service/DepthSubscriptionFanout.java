package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.product.api.ProductLine;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.api.model.WsServerMessage;
import com.surprising.websocket.provider.model.DepthUpdate;
import com.surprising.websocket.provider.session.ClientConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/** L2 深度订阅的基线追踪、增量编码与零拷贝扇出分发器。 */
final class DepthSubscriptionFanout {

    private record DepthBookKey(ProductLine productLine, String instrumentId) {}

    private static final class EncodedDepth {
        static final EncodedDepth EMPTY = new EncodedDepth(null, null);
        final String message;
        final ByteBuf frame;

        EncodedDepth(String message, ByteBuf frame) {
            this.message = message;
            this.frame = frame;
        }
    }

    private final ObjectMapper objectMapper;
    private final Map<SubscriptionTopic, Set<ClientConnection>> subscribers;
    private final LongAdder fanoutBatches;
    private final LongAdder fanoutMessages;
    private final LongAdder backpressureRejections;
    private final Consumer<ClientConnection> disconnectHandler;

    // Last book accepted by each connection queue; immutable Core views are shared, never copied.
    private final Map<SubscriptionTopic, Map<String, CoreOrderBookView>> depthBaselines = new ConcurrentHashMap<>();
    private final Map<DepthBookKey, Set<SubscriptionTopic>> depthTopics = new ConcurrentHashMap<>();

    DepthSubscriptionFanout(ObjectMapper objectMapper,
                            Map<SubscriptionTopic, Set<ClientConnection>> subscribers,
                            LongAdder fanoutBatches,
                            LongAdder fanoutMessages,
                            LongAdder backpressureRejections,
                            Consumer<ClientConnection> disconnectHandler) {
        this.objectMapper = objectMapper;
        this.subscribers = subscribers;
        this.fanoutBatches = fanoutBatches;
        this.fanoutMessages = fanoutMessages;
        this.backpressureRejections = backpressureRejections;
        this.disconnectHandler = disconnectHandler;
    }

    void registerTopic(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.DEPTH && topic.productLine() != null) {
            depthTopics.computeIfAbsent(
                    new DepthBookKey(topic.productLine(), topic.instrumentId()),
                    ignored -> ConcurrentHashMap.newKeySet()).add(topic);
        }
    }

    void removeBaseline(SubscriptionTopic topic, String sessionId) {
        var baselines = depthBaselines.get(topic);
        if (baselines == null) return;
        baselines.remove(sessionId);
        if (baselines.isEmpty()) depthBaselines.remove(topic);
    }

    void unregisterTopic(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.DEPTH && topic.productLine() != null) {
            var key = new DepthBookKey(topic.productLine(), topic.instrumentId());
            Set<SubscriptionTopic> activeDepthTopics = depthTopics.get(key);
            if (activeDepthTopics != null) {
                activeDepthTopics.remove(topic);
                if (activeDepthTopics.isEmpty()) {
                    depthTopics.remove(key, activeDepthTopics);
                }
            }
        }
    }

    void clear() {
        depthBaselines.clear();
        depthTopics.clear();
    }

    boolean hasSubscribers(ProductLine productLine, String instrumentId) {
        if (productLine == null || instrumentId == null) return false;
        Set<SubscriptionTopic> active = depthTopics.get(new DepthBookKey(productLine, instrumentId));
        return active != null && !active.isEmpty();
    }

    void publishDepth(SubscriptionTopic topic, CoreOrderBookView book,
                      String version, String entityId, Instant eventTime) {
        if (topic.channel() != WsChannel.DEPTH || topic.productLine() == null) {
            throw new IllegalArgumentException("depth requires an explicit product line");
        }
        Set<SubscriptionTopic> active = depthTopics.get(new DepthBookKey(topic.productLine(), topic.instrumentId()));
        if (active == null || active.isEmpty()) return;
        for (SubscriptionTopic subscribedTopic : active) {
            publishDepthForTopic(subscribedTopic, book, version, entityId, eventTime);
        }
    }

    private void publishDepthForTopic(SubscriptionTopic topic, CoreOrderBookView book,
                                      String version, String entityId, Instant eventTime) {
        var connections = subscribers.get(topic);
        if (connections == null || connections.isEmpty()) return;
        int maxDepth = topic.effectiveDepth();
        var baselines = depthBaselines.computeIfAbsent(topic, ignored -> new ConcurrentHashMap<>());
        List<ClientConnection> failed = null;
        var encodedByBaseline = new IdentityHashMap<CoreOrderBookView, EncodedDepth>();
        try {
            synchronized (baselines) {
                for (ClientConnection connection : connections) {
                    var previous = baselines.get(connection.id());
                    if (previous != null && book.exportSequence() <= previous.exportSequence()) continue;
                    EncodedDepth encoded = encodedByBaseline.get(previous);
                    if (encoded == null) {
                        var update = DepthUpdate.between(previous, book, maxDepth);
                        if (topic.depth() != null && previous != null && update.bids().isEmpty() && update.asks().isEmpty()) {
                            encoded = EncodedDepth.EMPTY;
                        } else {
                            String message = objectMapper.writeValueAsString(WsServerMessage.event(topic,
                                    new RealtimeWebSocketDispatcher.VersionedEvent(version, entityId, update), eventTime));
                            ByteBuf frame = connection.supportsZeroCopyFrames()
                                    ? ByteBufUtil.writeUtf8(ByteBufAllocator.DEFAULT, message)
                                    : null;
                            encoded = new EncodedDepth(message, frame);
                        }
                        encodedByBaseline.put(previous, encoded);
                    }
                    if (encoded == EncodedDepth.EMPTY) continue;
                    fanoutBatches.increment();
                    fanoutMessages.increment();
                    boolean accepted;
                    if (connection.supportsZeroCopyFrames()) {
                        ByteBuf frame = encoded.frame != null
                                ? encoded.frame
                                : ByteBufUtil.writeUtf8(ByteBufAllocator.DEFAULT, encoded.message);
                        if (encoded.frame == null) {
                            encoded = new EncodedDepth(encoded.message, frame);
                            encodedByBaseline.put(previous, encoded);
                        }
                        accepted = connection.sendFrames(List.of(frame), List.of(encoded.message));
                    } else {
                        accepted = connection.send(encoded.message);
                    }
                    if (accepted) baselines.put(connection.id(), book);
                    else {
                        backpressureRejections.increment();
                        if (failed == null) failed = new ArrayList<>();
                        failed.add(connection);
                    }
                }
            }
        } finally {
            for (EncodedDepth encoded : encodedByBaseline.values()) {
                if (encoded.frame != null) {
                    encoded.frame.release();
                }
            }
        }
        // Disconnect and lease cleanup may perform external I/O; never under the topic baseline lock.
        if (failed != null) for (ClientConnection connection : failed) disconnectHandler.accept(connection);
    }
}
