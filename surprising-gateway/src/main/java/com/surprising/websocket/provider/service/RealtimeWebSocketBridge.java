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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Component
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
    private final SnapshotAssembler snapshots = new SnapshotAssembler();

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
                        this::receive);
        heartbeat();
        registry.routeLifecycle(this);
    }

    @Override
    public synchronized void subscribed(SubscriptionTopic topic) {
        if (!receiver.ready()) throw new IllegalStateException("realtime receiver unavailable");
        RealtimeRoute route = route(topic);
        long now = System.currentTimeMillis();
        if (route.userId() > 0) refresh.renew(route.productLine(), route.userId(), now + 30000);
        else if (route.channel().equals("BOOK"))
            refresh.renewBook(route.productLine(), route.symbol(), now + 30000);
        directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
        directory.register(route, node, now + 15000);
        memberships.merge(route, 1, Integer::sum);
    }

    @Override
    public synchronized void unsubscribed(SubscriptionTopic topic) {
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
        } catch (RuntimeException ignored) {
            /* The lease still expires. */
        }
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void heartbeat() {
        if (!receiver.ready()) {
            directory.removeNode(node);
            return;
        }
        long now = System.currentTimeMillis();
        directory.heartbeat(node, endpoint, Duration.ofSeconds(15));
        memberships
                .keySet()
                .forEach(
                        route -> {
                            directory.register(route, node, now + 15000);
                            if (route.userId() > 0)
                                refresh.renew(route.productLine(), route.userId(), now + 30000);
                            else if (route.channel().equals("BOOK"))
                                refresh.renewBook(route.productLine(), route.symbol(), now + 30000);
                        });
    }

    public UserReadView snapshot(ProductLine product, long user) {
        long now = System.currentTimeMillis();
        refresh.renew(product, user, now + 30000);
        return UserReadView.from(views.read(product, user, now, 15000));
    }

    private void receive(RealtimeFrame f) {
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
                                "instrumentChangeId",
                                b.getLong(),
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
                    case TRADE -> {
                        var b =
                                java.nio.ByteBuffer.wrap(f.payload())
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        yield Map.of(
                                "instrumentChangeId",
                                b.getLong(),
                                "priceTicks",
                                b.getLong(),
                                "quantitySteps",
                                b.getLong(),
                                "coreSequence",
                                Long.toString(b.getLong()),
                                "side",
                                CoreOrderSide.values()[b.get()].name());
                    }
                    default -> mapper.readTree(f.payload());
                };
        String symbol = f.symbol().isBlank() ? SubscriptionTopic.WILDCARD : f.symbol();
        var topic =
                new SubscriptionTopic(
                        channel,
                        symbol,
                        channel == WsChannel.CANDLES ? f.entityId() : null,
                        f.userId() > 0 ? f.userId() : null,
                        f.productLine());
        registry.publish(
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
                            WsChannel.BOOK_TICKER, symbol, null, null, f.productLine()),
                    new VersionedEvent(
                            RealtimeVersion.of(f.sequence(), f.ordinal()), f.entityId(), ticker),
                    Instant.ofEpochMilli(f.timestamp()));
        }
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
                topic.symbol());
    }

    public record BookTicker(CoreBookLevelView bid, CoreBookLevelView ask) {}

    public record VersionedEvent(String version, String entityId, Object value) {}

    @PreDestroy
    @Override
    public void close() {
        registry.routeLifecycle(null);
        receiver.close();
        try {
            directory.removeNode(node);
        } catch (RuntimeException ignored) {
        }
    }
}
