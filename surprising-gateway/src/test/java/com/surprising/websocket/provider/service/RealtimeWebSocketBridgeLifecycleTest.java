package com.surprising.websocket.provider.service;

import com.surprising.product.api.ProductLine;
import com.surprising.websocket.provider.config.WebSocketProperties;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeWebSocketBridgeLifecycleTest {
    private static Set<Long> receivers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("realtime-aeron-receiver"))
                .map(Thread::threadId).collect(Collectors.toSet());
    }

    @Test void redisFailureDuringConstructionRollsBackTheStartedReceiver() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/bridge-rollback-" + UUID.randomUUID();
        var environment = new MockEnvironment().withProperty("surprising.realtime.directory", directory)
                .withProperty("surprising.realtime.ws.channel", "aeron:udp?endpoint=localhost:0");
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var unavailable = new IllegalStateException("Valkey unavailable");
        // A client may rethrow one cached failure instance from both startup and cleanup.
        doThrow(unavailable).when(values).set(anyString(), anyString(), any(java.time.Duration.class));
        when(redis.delete(anyString())).thenThrow(unavailable);
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var before = receivers();
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                .dirDeleteOnStart(true).dirDeleteOnShutdown(true))) {
            assertThatThrownBy(() -> new RealtimeWebSocketBridge(registry, redis, environment, new ObjectMapper()))
                    .isSameAs(unavailable).hasMessage("Valkey unavailable");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!before.containsAll(receivers()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(receivers()).isSubsetOf(before);
        }
    }

    @Test void pinnedMarkRoutesAreSeededAtConstructionAndPreservedAcrossRecovery() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/bridge-mark-" + UUID.randomUUID();
        var environment = new MockEnvironment().withProperty("surprising.realtime.directory", directory)
                .withProperty("surprising.realtime.ws.channel", "aeron:udp?endpoint=localhost:0");
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var bridge = new RealtimeWebSocketBridge(registry, redis, environment, new ObjectMapper())) {
            assertThat(bridge.hasPinnedMarkRouteForTest(ProductLine.SPOT)).isTrue();
            assertThat(bridge.hasPinnedMarkRouteForTest(ProductLine.LINEAR_PERPETUAL)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!bridge.ready() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(bridge.ready()).isTrue();
            clearInvocations(redis);
            bridge.simulateContinuityLossForTest();
            bridge.heartbeat();
            assertThat(bridge.hasPinnedMarkRouteForTest(ProductLine.SPOT)).isTrue();
            assertThat(bridge.hasPinnedMarkRouteForTest(ProductLine.LINEAR_PERPETUAL)).isTrue();
            verify(redis).execute(any(RedisScript.class),
                    eq(List.of("rt:route:{SPOT:public:MARK:*}", "rt:route:{SPOT:public:MARK:*}:versions")),
                    any(), any(), any());
            verify(redis).execute(any(RedisScript.class),
                    eq(List.of("rt:route:{LINEAR_PERPETUAL:public:MARK:*}", "rt:route:{LINEAR_PERPETUAL:public:MARK:*}:versions")),
                    any(), any(), any());
        }
    }

    @Test void oldSnapshotAgeDoesNotRejectWebSocketSnapshotButValidityStillDoes() throws Exception {
        String directory = System.getProperty("java.io.tmpdir") + "/bridge-snapshot-" + UUID.randomUUID();
        var environment = new MockEnvironment().withProperty("surprising.realtime.directory", directory)
                .withProperty("surprising.realtime.ws.channel", "aeron:udp?endpoint=localhost:0");
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        @SuppressWarnings("unchecked") org.springframework.data.redis.core.HashOperations<String, Object, Object> hashes =
                mock(org.springframework.data.redis.core.HashOperations.class);
        @SuppressWarnings("unchecked") org.springframework.data.redis.core.ZSetOperations<String, String> requests =
                mock(org.springframework.data.redis.core.ZSetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForZSet()).thenReturn(requests);
        when(values.get("rt:source:SPOT")).thenReturn("source");
        var fields = new java.util.HashMap<Object, Object>();
        fields.put("@fence", "100");
        fields.put("@epoch", "source");
        fields.put("@snapshotAt", "1");
        when(hashes.entries("rt:view:{SPOT:42}")).thenAnswer(ignored -> new java.util.HashMap<>(fields));
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        try (var driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(directory)
                .dirDeleteOnStart(true).dirDeleteOnShutdown(true));
             var bridge = new RealtimeWebSocketBridge(registry, redis, environment, new ObjectMapper())) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!bridge.ready() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(bridge.ready()).isTrue();
            assertThat(bridge.snapshot(ProductLine.SPOT, 42).status()).isEqualTo("READY");
            assertThat(bridge.snapshot(ProductLine.SPOT, 42).snapshotAt()).isEqualTo(1);
            when(values.get("rt:source:SPOT")).thenReturn("replacement");
            assertThat(bridge.snapshot(ProductLine.SPOT, 42).status()).isEqualTo("STALE");
            when(values.get("rt:source:SPOT")).thenReturn("source");
            fields.put("@invalid", "snapshot_unavailable");
            assertThat(bridge.snapshot(ProductLine.SPOT, 42).status()).isEqualTo("STALE");
            fields.remove("@invalid");
            fields.remove("@fence");
            assertThat(bridge.snapshot(ProductLine.SPOT, 42).status()).isEqualTo("INITIALIZING");
        }
    }

}
