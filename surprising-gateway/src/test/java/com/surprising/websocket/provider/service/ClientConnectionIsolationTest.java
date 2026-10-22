package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.session.ClientConnection;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClientConnectionIsolationTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void authorizationPublishesTokenAndDeadlineAtomically(boolean rotatingExpiredToken) throws Exception {
        var session = new ControlledSession("authorization-publication", false, 1);
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
            // The former implementation pauses after publishing token, before its deadline.
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
        var session = org.mockito.Mockito.mock(WebSocketSession.class);
        org.mockito.Mockito.when(session.getId()).thenReturn("independent-close");
        org.mockito.Mockito.when(session.isOpen()).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> { socketClosing.countDown(); releaseSocket.await(); return null; })
                .when(session).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
        var registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        var lifecycle = org.mockito.Mockito.mock(SubscriptionRegistry.RouteLifecycle.class);
        var topic = new SubscriptionTopic(WsChannel.DEPTH, "1", null, null, com.surprising.product.api.ProductLine.SPOT);
        registry.routeLifecycle(lifecycle);
        org.mockito.Mockito.doAnswer(call -> { cleanupEntered.countDown(); releaseCleanup.await(); return null; })
                .when(lifecycle).unsubscribed(topic);
        try (var connection = new ClientConnection(session, null, 8, Duration.ofSeconds(1));
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            registry.add(connection);
            registry.subscribe(connection, topic);
            connection.close(CloseStatus.POLICY_VIOLATION);
            assertThat(socketClosing.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanupEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var fanout = executor.submit(() -> registry.publishDepth(topic,
                    new com.surprising.aeron.protocol.CoreOrderBookView(1, List.of()), "v1", "book", Instant.now()));
            fanout.get(1, TimeUnit.SECONDS);
            assertThat(registry.subscriberCount(topic)).isZero();
            assertThat(registry.activeConnectionCount()).isZero();
        } finally { releaseSocket.countDown(); releaseCleanup.countDown(); }
    }

    @Test
    void writerPreservesBatchOrder() throws Exception {
        var session = new ControlledSession("batch-writer", false, 2);
        try (var connection = new ClientConnection(session, 1001L, 8, Duration.ofSeconds(1))) {
            assertThat(connection.sendBatch(List.of("one", "two"))).isTrue();
        assertThat(session.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(session.payloads()).containsExactly("one", "two");
        }
    }

    @Test
    void laterSendGetsItsOwnDeadline() throws Exception {
        var session = new ControlledSession("second-send", 1, 1);
        try (var connection = new ClientConnection(session, 1001L, 8, Duration.ofMillis(600))) {
            assertThat(connection.send("completed")).isTrue();
            assertThat(session.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(400);
            assertThat(connection.send("blocked")).isTrue();
            assertThat(session.firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // The first send's deadline passes, but the blocked second send has time left.
            assertThat(session.closed.await(300, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(session.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.closeStatus).isEqualTo(
                    CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
            assertThat(session.payloads()).containsExactly("completed");
        }
    }

    @Test
    void timesOutBlockedWriteWithoutClosingHealthyConnection() throws Exception {
        var blocked = new ControlledSession("blocked-timeout", true, 0);
        var healthy = new ControlledSession("healthy-timeout", false, 3);
        try (var slowConnection = new ClientConnection(blocked, 1001L, 8, Duration.ofMillis(100));
             var healthyConnection = new ClientConnection(healthy, 1002L, 8, Duration.ofMillis(100))) {
            assertThat(slowConnection.send("blocked")).isTrue();
            assertThat(blocked.firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(healthyConnection.sendBatch(List.of("one", "two", "three"))).isTrue();
            assertThat(healthy.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(blocked.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(blocked.closeStatus).isEqualTo(
                    CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
            assertThat(slowConnection.send("after-timeout")).isFalse();
            assertThat(healthy.open).isTrue();
            assertThat(healthy.payloads()).containsExactly("one", "two", "three");
        }
    }

    @Test
    void idleConnectionAndCompletedSendDoNotExpire() throws Exception {
        var session = new ControlledSession("idle", false, 2);
        try (var connection = new ClientConnection(session, 1001L, 8, Duration.ofMillis(100))) {
            assertThat(session.closed.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(connection.send("first")).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (session.payloads().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(session.payloads()).containsExactly("first");
            assertThat(session.closed.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(connection.send("second")).isTrue();
            assertThat(session.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.closed.await(250, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(session.payloads()).containsExactly("first", "second");
        }
        assertThat(session.closed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(session.closeStatus).isEqualTo(CloseStatus.NORMAL);
    }

    @Test
    void closesOnlyBlockedSessionAtQueueBound() throws Exception {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getSession().setOutboundQueueCapacity(2);
        properties.getSession().setSendTimeout(Duration.ofSeconds(30));
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), properties);
        ControlledSession slowSession = new ControlledSession("slow", true, 0);
        ControlledSession healthySession = new ControlledSession("healthy", false, 4);
        ClientConnection slow = new ClientConnection(slowSession, 1001L, 2, Duration.ofSeconds(30));
        ClientConnection healthy = new ClientConnection(healthySession, 1002L, 2, Duration.ofSeconds(30));
        SubscriptionTopic topic = new SubscriptionTopic(WsChannel.INDEX_PRICE, "1", null, null);

        registry.add(slow);
        registry.add(healthy);
        registry.subscribe(slow, topic);
        registry.subscribe(healthy, topic);

        try {
            registry.publish(topic, "event-0", Instant.parse("2026-08-16T00:00:00Z"));
            assertThat(slowSession.firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            healthySession.awaitMessageCount(1);
            // The slow writer is now actually blocked. Keep the healthy socket draining rather
            // than assuming the OS schedules its writer during an unpaced four-message burst.
            for (int index = 1; index < 4; index++) {
                registry.publish(topic, "event-" + index, Instant.parse("2026-08-16T00:00:00Z"));
                healthySession.awaitMessageCount(index + 1);
            }

            assertThat(slowSession.closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(healthySession.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(slowSession.closeStatus).isEqualTo(CloseStatus.SERVICE_OVERLOAD);
            assertThat(slow.queuedMessages()).isLessThanOrEqualTo(2);
            assertThat(healthySession.payloads()).hasSize(4);
            assertThat(registry.subscriberCount(topic)).isEqualTo(1);
        } finally {
            slow.close();
            healthy.close();
        }
    }

    private static final class ControlledSession implements WebSocketSession {

        private final String id;
        private final int blockedSendIndex;
        private final CountDownLatch expectedMessages;
        private final CountDownLatch firstSendEntered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final CountDownLatch releaseFirstSend = new CountDownLatch(1);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;
        private volatile CloseStatus closeStatus;
        private int sendCount;

        private ControlledSession(String id, boolean blockFirstSend, int expectedMessageCount) {
            this(id, blockFirstSend ? 0 : -1, expectedMessageCount);
        }

        private ControlledSession(String id, int blockedSendIndex, int expectedMessageCount) {
            this.id = id;
            this.blockedSendIndex = blockedSendIndex;
            this.expectedMessages = new CountDownLatch(expectedMessageCount);
            if (blockedSendIndex < 0) {
                firstSendEntered.countDown();
            }
        }

        private void awaitMessageCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (messages.size() < expected && System.nanoTime() < deadline) Thread.sleep(1);
            assertThat(messages).hasSize(expected);
        }

        private List<String> payloads() {
            return new ArrayList<>(messages);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public URI getUri() {
            return URI.create("ws://localhost/" + id);
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return new HttpHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return Map.of();
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSize) {
        }

        @Override
        public int getTextMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSize) {
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return 64 * 1024;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (sendCount++ == blockedSendIndex) {
                firstSendEntered.countDown();
                while (open) {
                    try {
                        releaseFirstSend.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return;
            }
            if (open) {
                messages.add(((TextMessage) message).getPayload());
                expectedMessages.countDown();
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            close(CloseStatus.NORMAL);
        }

        @Override
        public void close(CloseStatus status) {
            closeStatus = status;
            open = false;
            releaseFirstSend.countDown();
            closed.countDown();
        }
    }
}
