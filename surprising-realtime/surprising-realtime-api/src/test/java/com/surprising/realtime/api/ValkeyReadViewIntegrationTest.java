package com.surprising.realtime.api;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.util.*;

class ValkeyReadViewIntegrationTest {
    static Process server;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static ValkeyReadViewStore store;

    @BeforeAll
    static void start() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server =
                new ProcessBuilder(
                                System.getProperty("realtime.test.server", "redis-server"),
                                "--bind",
                                "127.0.0.1",
                                "--port",
                                Integer.toString(port),
                                "--save",
                                "",
                                "--appendonly",
                                "no")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        store = new ValkeyReadViewStore(redis);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (var connection = factory.getConnection()) {
                connection.ping();
                break;
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) throw e;
                Thread.sleep(20);
            }
        }
    }

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
        if (server != null) server.destroy();
    }

    @BeforeEach
    void clear() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void sourceEpochChangeAndUnavailableSnapshotCannotReturnReady() {
        store.sourceEpoch(ProductLine.SPOT, "first");
        store.install(snapshot(100, 1, 10), 1000, "first");
        assertThat(store.read(ProductLine.SPOT, 42, 1001, 100).status()).isEqualTo("READY");
        store.sourceEpoch(ProductLine.SPOT, "second");
        assertThat(store.read(ProductLine.SPOT, 42, 1001, 100).status()).isEqualTo("STALE");
        store.install(snapshot(101, 2, 11), 1001, "second");
        assertThat(store.read(ProductLine.SPOT, 42, 1002, 100).status()).isEqualTo("READY");
        store.unavailable(ProductLine.SPOT, 42);
        assertThat(store.read(ProductLine.SPOT, 42, 1002, 100).status()).isEqualTo("STALE");
    }

    @Test
    void delayedMembershipUpdatesCannotResurrectAnUnsubscribedNode() {
        var routes = new ValkeyRouteDirectory(redis);
        String node = UUID.randomUUID().toString();
        var route = new RealtimeRoute(ProductLine.SPOT, 42, "ORDERS", "BTCUSDT");
        assertThat(route)
                .isEqualTo(new RealtimeRoute(ProductLine.SPOT, 42, "POSITIONS", "ETHUSDT"));
        routes.heartbeat(
                node, "aeron:udp?endpoint=127.0.0.1:25001", java.time.Duration.ofSeconds(10));
        routes.membership(route, node, 2000, 1);
        routes.membership(route, node, 0, 3);
        routes.membership(route, node, 2000, 2);
        assertThat(routes.targets(route, 1000)).isEmpty();
    }

    @Test
    void initializationAndFreshnessAreExplicitAndDeltasCannotInitializeIncompleteViews() {
        assertThat(store.read(ProductLine.SPOT, 42, 100, 100).status()).isEqualTo("INITIALIZING");
        store.apply(balance(101, 1, 7));
        assertThat(store.read(ProductLine.SPOT, 42, 100, 100).status()).isEqualTo("INITIALIZING");
        store.install(snapshot(100, 9, 5), 1000);
        assertThat(
                        UserReadView.from(store.read(ProductLine.SPOT, 42, 1050, 100))
                                .account()
                                .balances()
                                .getFirst()
                                .availableUnits())
                .isEqualTo(7);
        assertThat(store.read(ProductLine.SPOT, 42, 1101, 100).status()).isEqualTo("STALE");
    }

    @Test
    void preservesVersionsAboveJavaScriptIntegerPrecisionAndPreventsResurrection() {
        long seq = 9_007_199_254_740_992L;
        store.install(snapshot(seq, 1, 10), 1000);
        assertThat(store.apply(balance(seq, 1, 1))).isFalse();
        assertThat(store.apply(balance(seq + 1, 1, 11))).isTrue();
        assertThat(store.apply(balance(seq + 1, 1, 99))).isFalse();
        assertThat(store.apply(balance(seq + 1, 0, 98))).isFalse();
        store.install(snapshot(seq, 2, 3), 1001);
        assertThat(
                        UserReadView.from(store.read(ProductLine.SPOT, 42, 1002, 100))
                                .account()
                                .balances()
                                .getFirst()
                                .availableUnits())
                .isEqualTo(11);
        store.install(snapshot(seq + 2, 3, 12), 1003);
        assertThat(store.apply(balance(seq + 1, 3, 0))).isFalse();
        assertThat(
                        UserReadView.from(store.read(ProductLine.SPOT, 42, 1004, 100))
                                .account()
                                .balances()
                                .getFirst()
                                .availableUnits())
                .isEqualTo(12);
    }

    @Test
    void rejectsGappedOrMixedSnapshotsAndKeepsProductsAndUsersSeparate() {
        var invalid = new ArrayList<>(snapshot(3, 1, 10));
        invalid.remove(1);
        assertThatThrownBy(() -> store.install(invalid, 1))
                .isInstanceOf(IllegalArgumentException.class);
        store.install(snapshot(3, 1, 10), 1);
        assertThat(store.read(ProductLine.SPOT, 43, 1, 100).status()).isEqualTo("INITIALIZING");
        var other =
                Arrays.stream(ProductLine.values())
                        .filter(p -> p != ProductLine.SPOT)
                        .findFirst()
                        .orElseThrow();
        assertThat(store.read(other, 42, 1, 100).status()).isEqualTo("INITIALIZING");
    }

    @Test
    void routesOnlyToLiveSubscribedNodeAndKeepsOtherProductsPrivate() {
        var routes = new ValkeyRouteDirectory(redis);
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        var route = new RealtimeRoute(ProductLine.SPOT, 42, "USER", "");
        routes.heartbeat(a, "aeron:udp?endpoint=127.0.0.1:25001", java.time.Duration.ofSeconds(10));
        routes.heartbeat(b, "aeron:udp?endpoint=127.0.0.1:25002", java.time.Duration.ofSeconds(10));
        routes.register(route, a, 2000);
        routes.register(new RealtimeRoute(ProductLine.SPOT, 43, "USER", ""), b, 2000);
        assertThat(routes.targets(route, 1000)).containsOnlyKeys(a);
        routes.removeNode(a);
        assertThat(routes.targets(route, 1000)).isEmpty();
    }

    @Test
    void snapshotAssemblerDiscardsPartialBatchesAndExpiresThem() {
        var assembler = new SnapshotAssembler();
        var frames = snapshot(10, 5, 1);
        assertThat(assembler.accept(frames.getFirst(), 0)).isEmpty();
        assertThat(assembler.accept(frames.getLast(), 1)).isEmpty();
        for (int i = 0; i < frames.size() - 1; i++)
            assertThat(assembler.accept(frames.get(i), 2)).isEmpty();
        assertThat(assembler.accept(frames.getLast(), 3)).hasSize(3);
        assembler.accept(frames.getFirst(), 0);
        assertThat(assembler.accept(frames.get(1), 6000)).isEmpty();
    }

    static RealtimeFrame balance(long seq, int ordinal, long units) {
        return new RealtimeFrame(
                ProductLine.SPOT,
                RealtimeFrame.Kind.BALANCE,
                42,
                seq,
                ordinal,
                1,
                0,
                "",
                "USDT",
                user(units));
    }

    static byte[] user(long units) {
        return CoreStateQueryCodec.encodeUserState(
                new CoreUserStateView(
                        ProductLine.SPOT,
                        42,
                        1,
                        List.of(new CoreBalanceView("USDT", units, 0)),
                        List.of(),
                        List.of()));
    }

    static List<RealtimeFrame> snapshot(long seq, long id, long units) {
        return List.of(
                new RealtimeFrame(
                        ProductLine.SPOT,
                        RealtimeFrame.Kind.SNAPSHOT_BEGIN,
                        42,
                        seq,
                        0,
                        1,
                        id,
                        "",
                        "",
                        new byte[0]),
                new RealtimeFrame(
                        ProductLine.SPOT,
                        RealtimeFrame.Kind.USER,
                        42,
                        seq,
                        1,
                        1,
                        id,
                        "",
                        "user",
                        user(units)),
                new RealtimeFrame(
                        ProductLine.SPOT,
                        RealtimeFrame.Kind.SNAPSHOT_END,
                        42,
                        seq,
                        2,
                        1,
                        id,
                        "",
                        "",
                        new byte[0]));
    }
}
