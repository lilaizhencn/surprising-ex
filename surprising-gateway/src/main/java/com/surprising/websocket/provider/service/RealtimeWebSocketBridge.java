package com.surprising.websocket.provider.service;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;
import com.surprising.websocket.api.model.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;

@Component
@org.springframework.context.annotation.DependsOn("appMediaDriver")
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public final class RealtimeWebSocketBridge
        implements SubscriptionRegistry.RouteLifecycle, AutoCloseable {
    private final String node = UUID.randomUUID().toString();
    private final String endpoint;
    private final SubscriptionRegistry registry;
    private final ValkeyRouteDirectory directory;
    private final ValkeyReadViewStore views;
    private final ValkeySnapshotRequests refresh;
    private final Map<RealtimeRoute, Integer> memberships = new HashMap<>();
    private final AeronRealtimeReceiver receiver;
    private final ObjectMapper mapper;
    private volatile SnapshotAssembler snapshots = new SnapshotAssembler();
    private volatile boolean closed;
    private long nextHeartbeatNanos;
    private final java.util.concurrent.atomic.AtomicLong nextCleanupLog = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean continuityLost = new java.util.concurrent.atomic.AtomicBoolean();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RealtimeWebSocketBridge.class);

    public RealtimeWebSocketBridge(
            SubscriptionRegistry registry,
            StringRedisTemplate redis,
            Environment env,
            ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
        directory = new ValkeyRouteDirectory(redis);
        views = new ValkeyReadViewStore(redis);
        refresh = new ValkeySnapshotRequests(redis);
        endpoint = env.getRequiredProperty("surprising.realtime.ws.channel");
        receiver =
                new AeronRealtimeReceiver(
                        env.getProperty(
                                "surprising.realtime.directory",
                                io.aeron.CommonContext.getAeronDirectoryName()),
                        endpoint,
                        env.getProperty("surprising.realtime.ws.stream", Integer.class, 2103),
                        this::receive, failure -> continuityLost.set(true));
        try {
            heartbeat();
            registry.routeLifecycle(this);
        } catch (RuntimeException | Error failure) {
            try { receiver.close(); } catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            try { directory.removeNode(node); } catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public synchronized void subscribed(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.OPEN_INTEREST) return;
        if (closed || continuityLost.get() || !receiver.ready())
            throw new IllegalStateException("realtime receiver unavailable");
        RealtimeRoute route = route(topic);
        int priorCount = memberships.getOrDefault(route, 0);
        long now = System.currentTimeMillis();
        try {
            directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
            directory.register(route, node, now + 15000);
            renewSnapshot(route, now);
            memberships.put(route, priorCount + 1);
        } catch (RuntimeException failure) {
            // A write can reach Redis before its response fails; clear an orphaned first lease.
            if (priorCount == 0) {
                try { directory.unregister(route, node); }
                catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private void renewSnapshot(RealtimeRoute route, long now) {
        if (route.userId() > 0) refresh.renew(route.productLine(), route.userId(), now + 30000);
        else if (route.channel().equals("BOOK"))
            refresh.renewBook(route.productLine(), route.instrumentId(), now + 30000);
    }

    @Override
    public synchronized void unsubscribed(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.OPEN_INTEREST) return;
        RealtimeRoute route = route(topic);
        Integer count = memberships.get(route);
        if (count == null) return;
        if (count > 1) {
            memberships.put(route, count - 1);
            return;
        }
        memberships.remove(route);
        try {
            directory.unregister(route, node);
        } catch (RuntimeException failure) {
            logCleanupFailure("route", failure);
        }
    }

    public void heartbeat() {
        if (closed) return;
        if (continuityLost.get() || receiver.recoveryRequired()) {
            long recoveryVersion = receiver.recoveryVersion();
            // Receiver has paused polling. Close clients before dropping old fences and resuming.
            registry.resetRealtime();
            synchronized (this) {
                snapshots = new SnapshotAssembler();
                memberships.clear();
                directory.removeNode(node);
                continuityLost.set(false);
                receiver.resumeAfterRecovery(recoveryVersion);
                nextHeartbeatNanos = 0;
            }
        }
        synchronized (this) {
            if (closed) return;
            long nanos = System.nanoTime();
            if (nextHeartbeatNanos != 0 && nanos < nextHeartbeatNanos) return;
            if (!receiver.ready()) {
                directory.removeNode(node);
                nextHeartbeatNanos = nanos + 5_000_000_000L;
                return;
            }
            long now = System.currentTimeMillis();
            directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
            memberships.keySet().forEach(route -> {
                directory.register(route, node, now + 15000);
                renewSnapshot(route, now);
            });
            nextHeartbeatNanos = nanos + 5_000_000_000L;
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    void registerMeters(io.micrometer.core.instrument.MeterRegistry meters) {
        io.micrometer.core.instrument.Gauge.builder("surprising.websocket.realtime.ready", this,
                bridge -> bridge.ready() ? 1 : 0).register(meters);
        io.micrometer.core.instrument.Gauge.builder("surprising.websocket.realtime.failures", this,
                RealtimeWebSocketBridge::decodeFailures).tag("stage", "decode").register(meters);
        io.micrometer.core.instrument.Gauge.builder("surprising.websocket.realtime.failures", this,
                RealtimeWebSocketBridge::handlerFailures).tag("stage", "handler").register(meters);
        io.micrometer.core.instrument.Gauge.builder("surprising.websocket.realtime.failures", this,
                RealtimeWebSocketBridge::transportFailures).tag("stage", "transport").register(meters);
    }

    public boolean ready() { return !closed && !continuityLost.get() && receiver.ready(); }
    public long decodeFailures() { return receiver.decodeFailures(); }
    public long handlerFailures() { return receiver.handlerFailures(); }
    public long transportFailures() { return receiver.transportFailures(); }

    public UserReadView snapshot(ProductLine product, long user) {
        if (!ready()) return UserReadView.from(new ValkeyReadViewStore.ReadView("UNAVAILABLE", "", 0, List.of()));
        long now = System.currentTimeMillis();
        refresh.renew(product, user, now + 30000);
        return UserReadView.from(views.read(product, user, now, 15000));
    }

    private void receive(RealtimeFrame f) {
        if (closed || continuityLost.get()) return;
        if (f.kind() == RealtimeFrame.Kind.SNAPSHOT_UNAVAILABLE) {
            registry.publishUserSnapshot(
                    f.productLine(),
                    f.userId(),
                    UserReadView.from(
                            new ValkeyReadViewStore.ReadView("UNAVAILABLE", "", 0, List.of())));
            return;
        }
        if (f.snapshotId() != 0) {
            var complete = snapshots.accept(f, System.currentTimeMillis());
            if (!complete.isEmpty()) {
                var view =
                        UserReadView.from(
                                new ValkeyReadViewStore.ReadView(
                                        "READY",
                                        RealtimeVersion.fence(f.sequence()),
                                        System.currentTimeMillis(),
                                        complete.subList(1, complete.size() - 1),
                                        RealtimeVersion.of(
                                                ValkeyReadViewStore.exportSequence(
                                                        complete.getLast()),
                                                0)));
                registry.publishUserSnapshot(f.productLine(), f.userId(), view);
            }
            return;
        }
        WsChannel channel =
                switch (f.kind()) {
                    case EXECUTION -> WsChannel.EXECUTION_REPORTS;
                    case RISK -> WsChannel.POSITION_RISK;
                    case ORDER -> WsChannel.ORDERS;
                    case TRIGGER -> WsChannel.TRIGGER_ORDERS;
                    case POSITION -> WsChannel.POSITIONS;
                    case USER, BALANCE, METADATA, RESERVATION, LEVERAGE -> WsChannel.ACCOUNT_STATE;
                    case TRADE -> WsChannel.TRADES;
                    case BOOK -> WsChannel.DEPTH;
                    case INDEX -> WsChannel.INDEX_PRICE;
                    case MARK -> WsChannel.MARK_PRICE;
                    case FUNDING -> WsChannel.FUNDING_RATE;
                    case CANDLE -> WsChannel.CANDLES;
                    default -> null;
                };
        if (channel == null) return;
        Object value =
                switch (f.kind()) {
                    case EXECUTION -> {
                        var b =
                                java.nio.ByteBuffer.wrap(f.payload())
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        yield Map.of(
                                "orderId",
                                Long.toString(b.getLong()),
                                "priceTicks",
                                b.getLong(),
                                "quantitySteps",
                                b.getLong(),
                                "side",
                                CoreOrderSide.values()[b.get()].name(),
                                "maker",
                                b.get() == 1);
                    }
                    case RISK -> UserReadView.PositionRisk.decode(f);
                    case BOOK -> CoreStateQueryCodec.decodeOrderBookView(f.payload());
                    case ORDER -> CoreStateQueryCodec.decodeOrderState(f.payload());
                    case TRIGGER -> CoreTriggerOrderCodec.decodeList(f.payload());
                    case USER, BALANCE, METADATA, RESERVATION, LEVERAGE, POSITION ->
                            CoreStateQueryCodec.decodeUserState(f.payload());
                    case TRADE -> publicTrade(f);
                    default -> mapper.readTree(f.payload());
                };
        String instrumentId = f.instrumentId().isBlank() ? SubscriptionTopic.WILDCARD : f.instrumentId();
        var topic =
                new SubscriptionTopic(
                        channel,
                        instrumentId,
                        channel == WsChannel.CANDLES ? f.entityId() : null,
                        f.userId() > 0 ? f.userId() : null,
                        f.productLine());
        if (f.kind() == RealtimeFrame.Kind.BOOK) {
            registry.publishDepth(topic, (CoreOrderBookView) value,
                    RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(),
                    Instant.ofEpochMilli(f.timestamp()));
        } else registry.publish(
                topic,
                new VersionedEvent(
                        RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), value),
                Instant.ofEpochMilli(f.timestamp()));
        if (f.kind() == RealtimeFrame.Kind.RISK) {
            registry.publish(
                    new SubscriptionTopic(
                            WsChannel.ACCOUNT_RISK,
                            SubscriptionTopic.WILDCARD,
                            null,
                            f.userId(),
                            f.productLine()),
                    new VersionedEvent(
                            RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), value),
                    Instant.ofEpochMilli(f.timestamp()));
        }
        if (f.kind() == RealtimeFrame.Kind.BOOK) {
            var book = (CoreOrderBookView) value;
            var bid =
                    book.levels().stream()
                            .filter(l -> l.side() == CoreOrderSide.BUY)
                            .max(Comparator.comparingLong(CoreBookLevelView::priceTicks))
                            .orElse(null);
            var ask =
                    book.levels().stream()
                            .filter(l -> l.side() == CoreOrderSide.SELL)
                            .min(Comparator.comparingLong(CoreBookLevelView::priceTicks))
                            .orElse(null);
            var ticker = new BookTicker(bid, ask);
            registry.publish(
                    new SubscriptionTopic(
                            WsChannel.BOOK_TICKER, instrumentId, null, null, f.productLine()),
                    new VersionedEvent(
                            RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), ticker),
                    Instant.ofEpochMilli(f.timestamp()));
        }
    }

    /** Public REST history uses this same entity ID; matcher sequence is a separate domain. */
    static Map<String, Object> publicTrade(RealtimeFrame frame) {
        var payload = java.nio.ByteBuffer.wrap(frame.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return Map.of(
                "tradeId", frame.entityId(),
                "instrumentId", frame.instrumentId(),
                "priceTicks", payload.getLong(),
                "quantitySteps", payload.getLong(),
                "coreSequence", Long.toString(payload.getLong()),
                "side", CoreOrderSide.values()[payload.get()].name(),
                "eventTime", Instant.ofEpochMilli(frame.timestamp()).toString());
    }

    private static RealtimeRoute route(SubscriptionTopic topic) {
        if (topic.productLine() == null)
            throw new IllegalArgumentException(
                    "productLine is required for realtime subscriptions");
        String channel =
                switch (topic.channel()) {
                    case TRADES -> "TRADE";
                    case DEPTH, BOOK_TICKER -> "BOOK";
                    case INDEX_PRICE -> "INDEX";
                    case MARK_PRICE -> "MARK";
                    case FUNDING_RATE -> "FUNDING";
                    case CANDLES -> "CANDLE";
                    default -> "USER";
                };
        return new RealtimeRoute(
                topic.productLine(),
                topic.userId() == null ? 0 : topic.userId(),
                channel,
                topic.instrumentId());
    }

    public record BookTicker(CoreBookLevelView bid, CoreBookLevelView ask) {}

    public record VersionedEvent(String version, String entityId, Object value) {}

    private void logCleanupFailure(String resource, RuntimeException failure) {
        long now = System.nanoTime();
        long next = nextCleanupLog.get();
        if ((next == 0 || now >= next) && nextCleanupLog.compareAndSet(next, now + 5_000_000_000L))
            LOG.warn("Realtime {} cleanup failed; lease will expire, error={}", resource, failure.getClass().getSimpleName());
    }

    @PreDestroy
    @Override
    public void close() {
        synchronized (this) {
            if (closed && receiver.terminated()) return;
            closed = true;
            registry.routeLifecycle(null);
            memberships.clear();
        }
        try { receiver.close(); }
        finally {
            try { directory.removeNode(node); }
            catch (RuntimeException failure) {
                logCleanupFailure("node", failure);
            }
        }
    }
}
