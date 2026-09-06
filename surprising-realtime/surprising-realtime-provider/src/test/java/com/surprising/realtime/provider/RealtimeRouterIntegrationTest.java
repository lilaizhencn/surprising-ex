package com.surprising.realtime.provider;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.client.AeronRealtimeReceiver;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.*;

import io.aeron.*;
import io.aeron.driver.MediaDriver;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.nio.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Real Aeron UDP and Redis-compatible Lua, including loss and node retirement. */
class RealtimeRouterIntegrationTest {
    @TempDir Path temporary;

    @Test
    void targetsOnlySubscribersAndHealsACommitGapWithAnAuthoritativeSnapshot() throws Exception {
        int port = port();
        Process server =
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
        var factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        factory.start();
        try {
            var redis = new StringRedisTemplate(factory);
            await(
                    () -> {
                        try (var connection = factory.getConnection()) {
                            return connection.ping() != null;
                        } catch (RuntimeException e) {
                            return false;
                        }
                    });
            String directory = temporary.resolve("aeron").toString();
            String endpointA = "aeron:udp?endpoint=127.0.0.1:" + port(),
                    endpointB = "aeron:udp?endpoint=127.0.0.1:" + port();
            var receivedA = new LinkedBlockingQueue<RealtimeFrame>();
            var receivedB = new LinkedBlockingQueue<RealtimeFrame>();
            var control = new LinkedBlockingQueue<RealtimeFrame>();
            var followerControl = new LinkedBlockingQueue<RealtimeFrame>();
            String leaderEndpoint = "aeron:udp?endpoint=127.0.0.1:" + port(),
                    followerEndpoint = "aeron:udp?endpoint=127.0.0.1:" + port();
            try (var driver =
                            MediaDriver.launch(
                                    new MediaDriver.Context()
                                            .aeronDirectoryName(directory)
                                            .dirDeleteOnStart(true)
                                            .dirDeleteOnShutdown(true));
                    var a = new AeronRealtimeReceiver(directory, endpointA, 2103, receivedA::add);
                    var b = new AeronRealtimeReceiver(directory, endpointB, 2103, receivedB::add);
                    var requests =
                            new AeronRealtimeReceiver(
                                    directory, leaderEndpoint, 2102, control::add);
                    var follower =
                            new AeronRealtimeReceiver(
                                    directory, followerEndpoint, 2102, followerControl::add);
                    var router =
                            new RealtimeRouter(
                                    new RealtimeRouterProperties(
                                            directory,
                                            "aeron:ipc",
                                            2101,
                                            2103,
                                            Map.of(
                                                    ProductLine.SPOT,
                                                    "aeron:udp?control-mode=manual"),
                                            Map.of(
                                                    ProductLine.SPOT,
                                                    List.of(leaderEndpoint, followerEndpoint))),
                                    redis);
                    var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
                    var publication = aeron.addExclusivePublication("aeron:ipc", 2101)) {
                await(
                        () ->
                                a.ready()
                                        && b.ready()
                                        && requests.ready()
                                        && publication.isConnected());
                var routes = new ValkeyRouteDirectory(redis);
                var views = new ValkeyReadViewStore(redis);
                String nodeA = UUID.randomUUID().toString(), nodeB = UUID.randomUUID().toString();
                routes.heartbeat(nodeA, endpointA, Duration.ofSeconds(30));
                routes.heartbeat(nodeB, endpointB, Duration.ofSeconds(30));
                var userRoute = new RealtimeRoute(ProductLine.SPOT, 42, "USER", "");
                routes.register(userRoute, nodeA, System.currentTimeMillis() + 30000);
                routes.register(
                        new RealtimeRoute(ProductLine.SPOT, 43, "USER", ""),
                        nodeB,
                        System.currentTimeMillis() + 30000);
                // Initial offers can precede UDP image establishment. Independent public updates
                // establish both images.
                var publicRoute = new RealtimeRoute(ProductLine.SPOT, 0, "MARK", "BTCUSDT");
                routes.register(publicRoute, nodeA, System.currentTimeMillis() + 30000);
                routes.register(publicRoute, nodeB, System.currentTimeMillis() + 30000);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (receivedA.isEmpty() || receivedB.isEmpty()) {
                    offer(
                            publication,
                            frame(
                                    RealtimeFrame.Kind.MARK,
                                    0,
                                    1,
                                    0,
                                    0,
                                    "BTCUSDT",
                                    "",
                                    new byte[] {'{', '}'}));
                    if (System.nanoTime() > deadline)
                        throw new AssertionError("node publications did not connect");
                    Thread.sleep(20);
                }
                receivedA.clear();
                receivedB.clear();
                commit(publication, 100, 0, 1, 7);
                await(() -> receivedA.stream().anyMatch(f -> f.userId() == 42));
                assertThat(receivedB).noneMatch(f -> f.userId() == 42);
                new ValkeySnapshotRequests(redis)
                        .renew(ProductLine.SPOT, 42, System.currentTimeMillis() + 30000);
                RealtimeFrame request = control.poll(5, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                assertThat(followerControl.poll(5, TimeUnit.SECONDS).snapshotId())
                        .isEqualTo(request.snapshotId());
                snapshot(publication, request.snapshotId(), 100, 1, 7);
                await(
                        () ->
                                views.read(ProductLine.SPOT, 42, System.currentTimeMillis(), 15000)
                                        .status()
                                        .equals("READY"));
                // An absent callback (export 1 -> 2) must invalidate a previously READY user view.
                commit(publication, 300, 2, 3, 9);
                await(
                        () ->
                                views.read(ProductLine.SPOT, 42, System.currentTimeMillis(), 15000)
                                        .status()
                                        .equals("STALE"));
                RealtimeFrame replacement = control.poll(5, TimeUnit.SECONDS);
                assertThat(replacement).isNotNull();
                snapshot(publication, replacement.snapshotId(), 300, 3, 9);
                await(
                        () ->
                                views.read(ProductLine.SPOT, 42, System.currentTimeMillis(), 15000)
                                        .status()
                                        .equals("READY"));
                assertThat(
                                UserReadView.from(
                                                views.read(
                                                        ProductLine.SPOT,
                                                        42,
                                                        System.currentTimeMillis(),
                                                        15000))
                                        .account()
                                        .balances()
                                        .getFirst()
                                        .availableUnits())
                        .isEqualTo(9);
                routes.unregister(userRoute, nodeA);
                receivedA.clear();
                commit(publication, 400, 3, 4, 11);
                Thread.sleep(250);
                assertThat(receivedA).noneMatch(f -> f.sequence() == 400 && f.userId() == 42);
                assertThat(receivedB).noneMatch(f -> f.userId() == 42);
                assertThat(router.failures()).isZero();
            }
        } finally {
            factory.destroy();
            server.destroy();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static void commit(
            ExclusivePublication p, long position, long previous, long end, long balance)
            throws Exception {
        offer(
                p,
                frame(
                        RealtimeFrame.Kind.COMMIT_BEGIN,
                        0,
                        position,
                        0,
                        0,
                        "",
                        "",
                        number(previous)));
        offer(p, frame(RealtimeFrame.Kind.BALANCE, 42, position, 1, 0, "", "USDT", user(balance)));
        offer(p, frame(RealtimeFrame.Kind.COMMIT_END, 0, position, 2, 0, "", "", number(end)));
    }

    private static void snapshot(
            ExclusivePublication p, long id, long position, long export, long balance)
            throws Exception {
        offer(
                p,
                frame(RealtimeFrame.Kind.SNAPSHOT_BEGIN, 42, position, 0, id, "", "", new byte[0]));
        offer(p, frame(RealtimeFrame.Kind.USER, 42, position, 1, id, "", "user", user(balance)));
        offer(
                p,
                frame(
                        RealtimeFrame.Kind.SNAPSHOT_END,
                        42,
                        position,
                        2,
                        id,
                        "",
                        "",
                        number(export)));
    }

    private static byte[] user(long balance) {
        return CoreStateQueryCodec.encodeUserState(
                new CoreUserStateView(
                        ProductLine.SPOT,
                        42,
                        1,
                        List.of(new CoreBalanceView("USDT", balance, 0)),
                        List.of(),
                        List.of()));
    }

    private static byte[] number(long n) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array();
    }

    private static RealtimeFrame frame(
            RealtimeFrame.Kind kind,
            long user,
            long seq,
            int ordinal,
            long id,
            String symbol,
            String entity,
            byte[] data) {
        return new RealtimeFrame(
                ProductLine.SPOT,
                kind,
                user,
                seq,
                ordinal,
                System.currentTimeMillis(),
                id,
                symbol,
                entity,
                data);
    }

    private static void offer(ExclusivePublication p, RealtimeFrame frame) throws Exception {
        var bytes = new UnsafeBuffer(RealtimeFrameCodec.encode(frame));
        await(() -> p.offer(bytes) > 0);
    }

    private static int port() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition timed out");
            Thread.sleep(10);
        }
    }
}
