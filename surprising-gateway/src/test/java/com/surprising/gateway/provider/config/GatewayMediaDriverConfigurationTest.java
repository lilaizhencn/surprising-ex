package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.*;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GatewayMediaDriverConfigurationTest {
    @TempDir Path temp;

    @Test void disabledRealtimeDoesNotStartDriver() {
        new ApplicationContextRunner().withUserConfiguration(GatewayMediaDriverConfiguration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(MediaDriver.class));
    }

    @Test void requiresExplicitDirectory() {
        new ApplicationContextRunner().withUserConfiguration(GatewayMediaDriverConfiguration.class)
                .withPropertyValues("surprising.realtime.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void sharesDriverWithExternalClientAndClosesWithContext(boolean udp) throws Exception {
        String directory = temp.resolve("app-aeron").toString();
        String channel;
        try (var socket = new java.net.DatagramSocket(0)) {
            channel = udp ? "aeron:udp?endpoint=127.0.0.1:" + socket.getLocalPort() : "aeron:ipc";
        }
        context(directory).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MediaDriver.class);
            assertThat(context.getBean(MediaDriver.class).context().threadingMode()).isEqualTo(ThreadingMode.SHARED);
            try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
                    Subscription subscription = aeron.addSubscription(channel, 2111)) {
                Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                        "-cp", System.getProperty("java.class.path"), ExternalPublisher.class.getName(), directory, channel)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    var value = new AtomicInteger();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (value.get() == 0 && System.nanoTime() < deadline) {
                        subscription.poll((buffer, offset, length, header) -> value.set(buffer.getInt(offset)), 10);
                        Thread.sleep(1);
                    }
                    assertThat(value.get()).isEqualTo(42);
                    assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(child.exitValue()).isZero();
                } finally {
                    if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            }
        });
        assertThat(io.aeron.CommonContext.isDriverActive(Path.of(directory).toFile(), 1000, message -> {})).isFalse();
        // The next gateway owns the same directory after a clean shutdown; no extra driver process.
        context(directory).run(context -> {
            assertThat(context).hasNotFailed();
            try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory))) {
                assertThat(aeron.isClosed()).isFalse();
            }
        });
    }

    @Test void websocketBridgeClosesBeforeItsDriver() throws Exception {
        String directory = temp.resolve("bridge-aeron").toString();
        var registry = org.mockito.Mockito.mock(com.surprising.websocket.provider.service.SubscriptionRegistry.class);
        var bridgeClosed = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            assertThat(io.aeron.CommonContext.isDriverActive(Path.of(directory).toFile(), 1000, message -> {})).isTrue();
            bridgeClosed.set(true);
            return null;
        }).when(registry).routeLifecycle(org.mockito.ArgumentMatchers.isNull());
        String channel;
        try (var socket = new java.net.DatagramSocket(0)) {
            channel = "aeron:udp?endpoint=127.0.0.1:" + socket.getLocalPort();
        }
        context(directory)
                .withUserConfiguration(com.surprising.websocket.provider.service.RealtimeWebSocketBridge.class)
                .withPropertyValues("surprising.realtime.ws.channel=" + channel)
                .withBean(com.surprising.websocket.provider.service.SubscriptionRegistry.class, () -> registry)
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class,
                                org.mockito.Mockito.RETURNS_DEEP_STUBS))
                .withBean(tools.jackson.databind.ObjectMapper.class, tools.jackson.databind.ObjectMapper::new)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(com.surprising.websocket.provider.service.RealtimeWebSocketBridge.class));
        assertThat(bridgeClosed).isTrue();
        assertThat(io.aeron.CommonContext.isDriverActive(Path.of(directory).toFile(), 1000, message -> {})).isFalse();
    }

    @Test void existingReceiverReconnectsWhenGatewayRestarts() throws Exception {
        String directory = temp.resolve("restart-aeron").toString();
        String channel;
        try (var socket = new java.net.DatagramSocket(0)) {
            channel = "aeron:udp?endpoint=127.0.0.1:" + socket.getLocalPort();
        }
        var received = new java.util.concurrent.atomic.AtomicLong();
        try (var receiver = new com.surprising.aeron.client.AeronRealtimeReceiver(
                directory, channel, 2112, frame -> received.set(frame.sequence()))) {
            context(directory).run(context -> {
                assertThat(context).hasNotFailed();
                publishUntilReceived(directory, channel, received, 1);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (receiver.ready() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(receiver.ready()).isFalse();
            context(directory).run(context -> {
                assertThat(context).hasNotFailed();
                publishUntilReceived(directory, channel, received, 2);
            });
        }
    }

    private void publishUntilReceived(String directory, String channel,
            java.util.concurrent.atomic.AtomicLong received, long sequence) throws Exception {
        try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory));
                var publication = aeron.addExclusivePublication(channel, 2112)) {
            byte[] bytes = com.surprising.aeron.protocol.RealtimeFrameCodec.encode(
                    new com.surprising.aeron.protocol.RealtimeFrame(
                            com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                            com.surprising.aeron.protocol.RealtimeFrame.Kind.TRADE,
                            0, sequence, 0, 1700000000000L, 0, "BTC-USDT", "fill", new byte[25]));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (received.get() != sequence && System.nanoTime() < deadline) {
                publication.offer(new UnsafeBuffer(bytes));
                Thread.sleep(10);
            }
            assertThat(received.get()).isEqualTo(sequence);
        }
    }

    @Test void refusesToReplaceAnActiveDriver() {
        String directory = temp.resolve("owned-aeron").toString();
        context(directory).run(owner -> {
            assertThat(owner).hasNotFailed();
            context(directory).run(second -> assertThat(second).hasFailed());
            try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory))) {
                assertThat(aeron.isClosed()).isFalse();
            }
        });
    }

    private ApplicationContextRunner context(String directory) {
        return new ApplicationContextRunner().withUserConfiguration(GatewayMediaDriverConfiguration.class)
                .withPropertyValues("surprising.realtime.enabled=true", "surprising.realtime.directory=" + directory);
    }

    public static class ExternalPublisher {
        public static void main(String[] args) throws Exception {
            try (var aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(args[0]));
                    ExclusivePublication publication = aeron.addExclusivePublication(args[1], 2111)) {
                var buffer = new UnsafeBuffer(new byte[4]);
                buffer.putInt(0, 42);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (publication.offer(buffer) < 0) {
                    if (System.nanoTime() > deadline) throw new IllegalStateException("publication timed out");
                    Thread.sleep(1);
                }
                // Keep the publication alive until the subscriber has polled the message.
                Thread.sleep(500);
            }
        }
    }
}
