package com.surprising.websocket.provider.service;

import com.surprising.websocket.provider.session.ClientConnection;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSessionAuthorizationTest {
    @Test void socketCloseCleansRoutesWhileItsAuthorizationCallIsStillBlocked() throws Exception {
        var registry = new SubscriptionRegistry(new tools.jackson.databind.ObjectMapper(), new com.surprising.websocket.provider.config.WebSocketProperties());
        var authenticator = mock(WebSocketAuthenticator.class);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("blocked-authorization");
        when(session.isOpen()).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(authenticator.principal("old")).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            throw new IllegalArgumentException("old session revoked");
        });
        var authorization = new WebSocketSessionAuthorization(registry, authenticator, Optional.empty());
        try (var connection = new ClientConnection(session, null, 8, Duration.ofSeconds(1));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.add(connection);
            connection.authenticate(7, "old", Instant.now().plusSeconds(60));
            var topic = new com.surprising.websocket.api.model.SubscriptionTopic(com.surprising.websocket.api.model.WsChannel.ORDERS,
                    "*", null, 7L, com.surprising.product.api.ProductLine.SPOT);
            registry.subscribe(connection, topic);
            var validating = executor.submit(() -> authorization.revalidate(connection));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            connection.close(CloseStatus.POLICY_VIOLATION);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (registry.activeConnectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(registry.activeConnectionCount()).isZero();
            assertThat(registry.subscriberCount(topic)).isZero();
            assertThat(validating.isDone()).isFalse();
            release.countDown();
            validating.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void oldSlowValidationFailureCannotRevokeARotatedToken() throws Exception {
        var registry = mock(SubscriptionRegistry.class);
        var authenticator = mock(WebSocketAuthenticator.class);
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("rotation");
        when(session.isOpen()).thenReturn(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(authenticator.principal("old")).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            throw new IllegalArgumentException("old session revoked");
        });
        var authorization = new WebSocketSessionAuthorization(registry, authenticator, Optional.empty());
        try (var connection = new ClientConnection(session, null, 8, Duration.ofSeconds(1));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connection.authenticate(7, "old", Instant.now().plusSeconds(60));
            var validating = executor.submit(() -> authorization.revalidate(connection));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            connection.authenticate(7, "new", Instant.now().plusSeconds(60));
            release.countDown();
            validating.get(1, TimeUnit.SECONDS);
            assertThat(connection.isOpen()).isTrue();
            assertThat(connection.accessToken()).isEqualTo("new");
            verify(registry, never()).remove(anyString());
            verify(session, never()).close(any(CloseStatus.class));
        } finally { release.countDown(); }
    }
}
