package com.surprising.websocket.provider.service;

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

    @Test
    void writerUsesEachQueuedRootAndDoesNotChangeCallerContext() throws Exception {
        var session = new ControlledSession("traced-writer", false, 2);
        try (var scope = com.surprising.trading.api.TraceContext.open("caller");
             var connection = new ClientConnection(session, 1001L, 8, Duration.ofSeconds(1))) {
            assertThat(connection.sendTracedBatch(List.of(
                    new ClientConnection.TracedMessage("one", "root-1"),
                    new ClientConnection.TracedMessage("two", "root-2")))).isTrue();
            assertThat(session.expectedMessages.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(session.traceIds).containsExactly("root-1", "root-2");
            assertThat(com.surprising.trading.api.TraceContext.current()).isEqualTo("caller");
        }
        assertThat(com.surprising.trading.api.TraceContext.current()).isNull();
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
            for (int index = 0; index < 4; index++) {
                registry.publish(topic, "event-" + index, Instant.parse("2026-08-16T00:00:00Z"));
            }

            assertThat(slowSession.firstSendEntered.await(2, TimeUnit.SECONDS)).isTrue();
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
        private final List<String> traceIds = new CopyOnWriteArrayList<>();
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
                traceIds.add(com.surprising.trading.api.TraceContext.current());
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
