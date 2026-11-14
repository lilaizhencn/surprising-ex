package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.session.ClientConnection;
import com.surprising.websocket.provider.session.WsCloseStatus;
import com.surprising.websocket.provider.session.WsTransportSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClientConnectionIsolationTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void authorizationPublishesTokenAndDeadlineAtomically(boolean rotatingExpiredToken) throws Exception {
        var session = new ControlledSession("authorization-publication", 0, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pause = new java.util.concurrent.atomic.AtomicBoolean();
        try (var connection = new ClientConnection(session, null, 8, Duration.ofSeconds(1)) {
            @Override public synchronized void renewAuthorization(String token, Instant expiry) {
                if (pause.compareAndSet(true, false)) {
                    entered.countDown();
                    try { assertThat(release.await(2, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                }
                super.renewAuthorization(token, expiry);
            }
        }; var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            if (rotatingExpiredToken) connection.authenticate(7, "old", Instant.now().minusSeconds(1));
            pause.set(true);
            var authenticating = executor.submit(() -> connection.authenticate(7, "new", Instant.now().plusSeconds(60)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!authenticating.isDone() && entered.getCount() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
            assertThat(connection.send("public-ticker")).isTrue();
            release.countDown();
            authenticating.get(1, TimeUnit.SECONDS);
            assertThat(connection.isOpen()).isTrue();
            assertThat(connection.accessToken()).isEqualTo("new");
            assertThat(connection.authorizationExpired(System.currentTimeMillis())).isFalse();
            assertThat(session.expectedMessages.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }

    @Test
    void receiverFanoutNeverWaitsForBlockedSocketCloseOrLeaseCleanup() throws Exception {
        var socketClosing = new CountDownLatch(1);
        var releaseSocket = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var session = org.mockito.Mockito.mock(WsTransportSession.class);
        org.mockito.Mockito.when(session.id()).thenReturn("independent-close");
        org.mockito.Mockito.when(session.isOpen()).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> { socketClosing.countDown(); releaseSocket.await(); return null; })
                .when(session).close(org.mockito.ArgumentMatchers.any(WsCloseStatus.class));
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var lifecycle = org.mockito.Mockito.mock(SubscriptionRegistry.RouteLifecycle.class);
        var topic = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, com.surprising.product.api.ProductLine.SPOT);
        registry.routeLifecycle(lifecycle);
        org.mockito.Mockito.doAnswer(call -> { cleanupEntered.countDown(); releaseCleanup.await(); return null; })
                .when(lifecycle).unsubscribed(topic);
        try (var connection = new ClientConnection(session, null);
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            registry.add(connection);
            registry.subscribe(connection, topic);
            var closing = executor.submit(() -> connection.close(WsCloseStatus.POLICY_VIOLATION));
            assertThat(socketClosing.await(1, TimeUnit.SECONDS)).isTrue();
            releaseSocket.countDown();
            assertThat(cleanupEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var fanout = executor.submit(() -> registry.publishDepth(topic,
                    new com.surprising.aeron.protocol.CoreOrderBookView(1, List.of()), "v1", "book", Instant.now()));
            fanout.get(1, TimeUnit.SECONDS);
            releaseCleanup.countDown();
            closing.get(1, TimeUnit.SECONDS);
            assertThat(registry.subscriberCount(topic)).isZero();
            assertThat(registry.activeConnectionCount()).isZero();
        } finally { releaseSocket.countDown(); releaseCleanup.countDown(); }
    }

    @Test
    void writerPreservesBatchOrder() throws Exception {
        var session = new ControlledSession("batch-writer", 0, 2);
        try (var connection = new ClientConnection(session, 1001L)) {
            assertThat(connection.sendBatch(List.of("one", "two"))).isTrue();
            assertThat(session.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.payloads()).containsExactly("one", "two");
        }
    }

    @Test
    void closesOnlyUnwritableSessionOnBackpressure() throws Exception {
        WebSocketProperties properties = new WebSocketProperties();
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), properties);
        ControlledSession slowSession = new ControlledSession("slow", 1, 1);
        ControlledSession healthySession = new ControlledSession("healthy", 0, 4);
        ClientConnection slow = new ClientConnection(slowSession, 1001L);
        ClientConnection healthy = new ClientConnection(healthySession, 1002L);
        SubscriptionTopic topic = new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null);

        registry.add(slow);
        registry.add(healthy);
        registry.subscribe(slow, topic);
        registry.subscribe(healthy, topic);

        try {
            for (int index = 0; index < 4; index++) {
                registry.publish(topic, "event-" + index, Instant.parse("2026-08-16T00:00:00Z"));
            }

            assertThat(slowSession.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(healthySession.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(slowSession.closeStatus).isEqualTo(WsCloseStatus.SERVICE_OVERLOAD);
            assertThat(healthySession.payloads()).hasSize(4);
            assertThat(registry.subscriberCount(topic)).isEqualTo(1);
        } finally {
            slow.close();
            healthy.close();
        }
    }

    private static final class ControlledSession implements WsTransportSession {

        private final String id;
        private final int rejectAfterCount;
        private final CountDownLatch expectedMessages;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;
        private volatile WsCloseStatus closeStatus;

        private ControlledSession(String id, int rejectAfterCount, int expectedMessageCount) {
            this.id = id;
            this.rejectAfterCount = rejectAfterCount;
            this.expectedMessages = new CountDownLatch(expectedMessageCount);
        }

        private List<String> payloads() {
            return new ArrayList<>(messages);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String clientIp() {
            return "127.0.0.1";
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean sendText(String payload) {
            if (!open) {
                return false;
            }
            if (rejectAfterCount > 0 && messages.size() >= rejectAfterCount) {
                return false;
            }
            messages.add(payload);
            expectedMessages.countDown();
            return true;
        }

        @Override
        public void close(WsCloseStatus status) {
            closeStatus = status;
            open = false;
            closed.countDown();
        }
    }
}
