package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.config.WebSocketProperties;
import io.aeron.driver.MediaDriver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
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
}
