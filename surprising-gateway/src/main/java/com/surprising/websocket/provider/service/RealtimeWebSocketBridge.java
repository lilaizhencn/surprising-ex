package com.surprising.websocket.provider.service;

import com.surprising.aeron.client.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;
import com.surprising.websocket.api.model.*;
import com.surprising.websocket.provider.config.WebSocketProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@org.springframework.context.annotation.DependsOn("appMediaDriver")
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true", matchIfMissing = true)
public final class RealtimeWebSocketBridge
        implements SubscriptionRegistry.RouteLifecycle, AutoCloseable {
    private final String node = UUID.randomUUID().toString();
    private final String endpoint;
    private final SubscriptionRegistry registry;
    private final ValkeyRouteDirectory directory;
    private final ValkeyReadViewStore views;
    private final ValkeySnapshotRequests refresh;
    private final Map<RealtimeRoute, Integer> memberships = new ConcurrentHashMap<>();
    private final Object heartbeatLock = new Object();
    private final AeronRealtimeReceiver receiver;
    private record BookKey(ProductLine productLine, String instrumentId) {}
    private record LastTicker(long bidPrice, long bidQty, long askPrice, long askQty) {}
    private record PendingDepth(SubscriptionTopic topic, CoreOrderBookView book,
                                String version, String entityId, Instant eventTime) {}

    private final long depthCoalesceWindowNanos;
    private final Map<BookKey, LastTicker> lastTickers = new ConcurrentHashMap<>();
    private final Map<BookKey, PendingDepth> pendingDepths = new ConcurrentHashMap<>();
    private final Map<BookKey, Long> lastDepthFlushNanos = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService depthFlusher;
    private final java.util.concurrent.atomic.AtomicBoolean depthFlushScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile SnapshotAssembler snapshots = new SnapshotAssembler();
    private volatile boolean closed;
    private long nextHeartbeatNanos;
    private final java.util.concurrent.atomic.AtomicLong nextCleanupLog = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean continuityLost = new java.util.concurrent.atomic.AtomicBoolean();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RealtimeWebSocketBridge.class);
    private static final CoreOrderSide[] ORDER_SIDES = CoreOrderSide.values();

    public RealtimeWebSocketBridge(
            SubscriptionRegistry registry,
            StringRedisTemplate redis,
            Environment env,
            ObjectMapper mapper) {
        this(registry, redis, env, mapper, new WebSocketProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RealtimeWebSocketBridge(
            SubscriptionRegistry registry,
            StringRedisTemplate redis,
            Environment env,
            ObjectMapper mapper,
            WebSocketProperties properties) {
        this.registry = registry;
        Duration coalesce = properties == null ? Duration.ofMillis(50) : properties.getFanout().getDepthCoalesceWindow();
        this.depthCoalesceWindowNanos = coalesce == null || coalesce.isNegative() || coalesce.isZero()
                ? 0L : coalesce.toNanos();
        this.depthFlusher = depthCoalesceWindowNanos > 0
                ? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ws-depth-coalesce");
                    t.setDaemon(true);
                    return t;
                })
                : null;
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
            seedPinnedRoutes();
            heartbeat();
            registry.routeLifecycle(this);
        } catch (RuntimeException | Error failure) {
            if (depthFlusher != null) depthFlusher.shutdownNow();
            try { receiver.close(); } catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            try { directory.removeNode(node); } catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public void subscribed(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.OPEN_INTEREST) return;
        if (closed || continuityLost.get() || !receiver.ready())
            throw new IllegalStateException("realtime receiver unavailable");
        RealtimeRoute route = route(topic);
        boolean[] firstSubscriber = {false};
        memberships.compute(route, (ignored, priorCount) -> {
            firstSubscriber[0] = priorCount == null || priorCount == 0;
            return (priorCount == null ? 0 : priorCount) + 1;
        });
        if (!firstSubscriber[0]) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            if (nextHeartbeatNanos == 0) {
                directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
            }
            directory.register(route, node, now + 15000);
            renewSnapshot(route, now);
        } catch (RuntimeException failure) {
            boolean[] shouldUnregister = {false};
            memberships.computeIfPresent(route, (ignored, current) -> {
                if (current <= 1) {
                    shouldUnregister[0] = true;
                    return null;
                }
                return current - 1;
            });
            // A write can reach Redis before its response fails; clear an orphaned first lease.
            if (shouldUnregister[0]) {
                try { directory.unregister(route, node); }
                catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private void seedPinnedRoutes() {
        for (ProductLine product : ProductLine.values()) {
            memberships.put(new RealtimeRoute(product, 0, "MARK", "*"), 1);
        }
    }

    void simulateContinuityLossForTest() {
        continuityLost.set(true);
    }

    boolean hasPinnedMarkRouteForTest(ProductLine product) {
        return memberships.containsKey(new RealtimeRoute(product, 0, "MARK", "*"));
    }

    private void renewSnapshot(RealtimeRoute route, long now) {
        if (route.userId() > 0) refresh.renew(route.productLine(), route.userId(), now + 30000);
        else if (route.channel().equals("BOOK"))
            refresh.renewBook(route.productLine(), route.instrumentId(), now + 30000);
    }

    @Override
    public void unsubscribed(SubscriptionTopic topic) {
        if (topic.channel() == WsChannel.OPEN_INTEREST) return;
        RealtimeRoute route = route(topic);
        boolean[] lastSubscriber = {false};
        memberships.computeIfPresent(route, (ignored, count) -> {
            if (count <= 1) {
                lastSubscriber[0] = true;
                return null;
            }
            return count - 1;
        });
        if (lastSubscriber[0]) {
            try {
                directory.unregister(route, node);
            } catch (RuntimeException failure) {
                logCleanupFailure("route", failure);
            }
        }
    }

    public void heartbeat() {
        if (closed) return;
        if (continuityLost.get() || receiver.recoveryRequired()) {
            long recoveryVersion = receiver.recoveryVersion();
            // Receiver has paused polling. Close clients before dropping old fences and resuming.
            registry.resetRealtime();
            synchronized (heartbeatLock) {
                snapshots = new SnapshotAssembler();
                memberships.clear();
                seedPinnedRoutes();
                pendingDepths.clear();
                lastDepthFlushNanos.clear();
                lastTickers.clear();
                directory.removeNode(node);
                continuityLost.set(false);
                receiver.resumeAfterRecovery(recoveryVersion);
                nextHeartbeatNanos = 0;
            }
        }
        synchronized (heartbeatLock) {
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
            for (RealtimeRoute route : memberships.keySet()) {
                directory.register(route, node, now + 15000);
                renewSnapshot(route, now);
            }
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
        return UserReadView.from(views.read(product, user));
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
        String instrumentId = f.instrumentId().isBlank() ? SubscriptionTopic.WILDCARD : f.instrumentId();
        var topic =
                new SubscriptionTopic(
                        channel,
                        instrumentId,
                        channel == WsChannel.CANDLES ? f.entityId() : null,
                        f.userId() > 0 ? f.userId() : null,
                        f.productLine());
        if (f.kind() == RealtimeFrame.Kind.BOOK) {
            boolean hasDepth = registry.hasDepthSubscribers(f.productLine(), instrumentId);
            var tickerTopic = new SubscriptionTopic(
                    WsChannel.BOOK_TICKER, instrumentId, null, null, f.productLine());
            boolean hasTicker = registry.hasSubscribers(tickerTopic);
            if (!hasDepth && !hasTicker) return;
            CoreOrderBookView book = CoreStateQueryCodec.decodeOrderBookView(f.payload());
            String version = RealtimeVersion.of(f.sequence(), f.ordinal());
            Instant eventTime = Instant.ofEpochMilli(f.timestamp());
            BookKey key = new BookKey(f.productLine(), instrumentId);
            if (hasDepth) {
                publishOrCoalesceDepth(key, topic, book, version, f.entityId(), eventTime);
            }
            if (hasTicker) {
                publishTickerIfChanged(key, tickerTopic, book, version, f.entityId(), eventTime);
            }
            return;
        }
        if (f.kind() == RealtimeFrame.Kind.RISK) {
            var accountRiskTopic = new SubscriptionTopic(
                    WsChannel.ACCOUNT_RISK, SubscriptionTopic.WILDCARD, null, f.userId(), f.productLine());
            boolean hasPositionRisk = registry.hasSubscribers(topic);
            boolean hasAccountRisk = registry.hasSubscribers(accountRiskTopic);
            if (!hasPositionRisk && !hasAccountRisk) return;
            Object value = UserReadView.PositionRisk.decode(f);
            var event = new VersionedEvent(RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), value);
            Instant eventTime = Instant.ofEpochMilli(f.timestamp());
            if (hasPositionRisk) registry.publish(topic, event, eventTime);
            if (hasAccountRisk) registry.publish(accountRiskTopic, event, eventTime);
            return;
        }
        if (!registry.hasSubscribers(topic)) return;
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
                                ORDER_SIDES[b.get()].name(),
                                "maker",
                                b.get() == 1);
                    }
                    case ORDER -> CoreStateQueryCodec.decodeOrderState(f.payload());
                    case TRIGGER -> CoreTriggerOrderCodec.decodeList(f.payload());
                    case USER, BALANCE, METADATA, RESERVATION, LEVERAGE, POSITION ->
                            CoreStateQueryCodec.decodeUserState(f.payload());
                    case TRADE -> publicTrade(f);
                    default -> new tools.jackson.databind.util.RawValue(
                            new String(f.payload(), java.nio.charset.StandardCharsets.UTF_8));
                };
        registry.publish(
                topic,
                new VersionedEvent(
                        RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), value),
                Instant.ofEpochMilli(f.timestamp()));
    }

    private void publishOrCoalesceDepth(BookKey key, SubscriptionTopic topic, CoreOrderBookView book,
            String version, String entityId, Instant eventTime) {
        if (depthCoalesceWindowNanos <= 0 || depthFlusher == null) {
            registry.publishDepth(topic, book, version, entityId, eventTime);
            return;
        }
        long nowNanos = System.nanoTime();
        Long lastFlush = lastDepthFlushNanos.get(key);
        if (lastFlush == null || nowNanos - lastFlush >= depthCoalesceWindowNanos) {
            pendingDepths.remove(key);
            lastDepthFlushNanos.put(key, nowNanos);
            registry.publishDepth(topic, book, version, entityId, eventTime);
            return;
        }
        pendingDepths.put(key, new PendingDepth(topic, book, version, entityId, eventTime));
        if (depthFlushScheduled.compareAndSet(false, true)) {
            depthFlusher.schedule(this::flushPendingDepths, depthCoalesceWindowNanos,
                    java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void flushPendingDepths() {
        depthFlushScheduled.set(false);
        if (closed || continuityLost.get() || pendingDepths.isEmpty()) {
            return;
        }
        long nowNanos = System.nanoTime();
        for (var entry : pendingDepths.entrySet()) {
            BookKey key = entry.getKey();
            PendingDepth pending = pendingDepths.remove(key);
            if (pending == null) continue;
            lastDepthFlushNanos.put(key, nowNanos);
            try {
                registry.publishDepth(pending.topic(), pending.book(),
                        pending.version(), pending.entityId(), pending.eventTime());
            } catch (RuntimeException failure) {
                LOG.warn("Coalesced depth publish failed key={} error={}", key, failure.getMessage());
            }
        }
    }

    private void publishTickerIfChanged(BookKey key, SubscriptionTopic tickerTopic, CoreOrderBookView book,
            String version, String entityId, Instant eventTime) {
        CoreBookLevelView bid = null;
        CoreBookLevelView ask = null;
        for (CoreBookLevelView level : book.levels()) {
            if (level.side() == CoreOrderSide.BUY) {
                if (bid == null || level.priceTicks() > bid.priceTicks()) bid = level;
            } else if (level.side() == CoreOrderSide.SELL) {
                if (ask == null || level.priceTicks() < ask.priceTicks()) ask = level;
            }
        }
        LastTicker current = new LastTicker(
                bid == null ? 0L : bid.priceTicks(),
                bid == null ? 0L : bid.quantitySteps(),
                ask == null ? 0L : ask.priceTicks(),
                ask == null ? 0L : ask.quantitySteps());
        if (current.equals(lastTickers.put(key, current))) {
            return;
        }
        registry.publish(tickerTopic, new VersionedEvent(version, entityId, new BookTicker(bid, ask)), eventTime);
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
                "side", ORDER_SIDES[payload.get()].name(),
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
            pendingDepths.clear();
            lastDepthFlushNanos.clear();
            lastTickers.clear();
        }
        if (depthFlusher != null) depthFlusher.shutdownNow();
        try { receiver.close(); }
        finally {
            try { directory.removeNode(node); }
            catch (RuntimeException failure) {
                logCleanupFailure("node", failure);
            }
        }
    }
}
