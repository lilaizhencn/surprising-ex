package com.surprising.websocket.provider.task;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.websocket.provider.config.WebSocketAuthorizationConfiguration;
import com.surprising.websocket.provider.config.WebSocketProperties;
import com.surprising.websocket.provider.service.SubscriptionRegistry;
import com.surprising.websocket.provider.service.WebSocketAuthenticator;
import com.surprising.websocket.provider.service.WebSocketSessionAuthorization;
import com.surprising.websocket.provider.session.ClientConnection;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketSessionAuthorizationTaskTest {
    private ClientConnection connection(String token) {
        var connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn(token);
        when(connection.accessToken()).thenReturn(token);
        when(connection.authenticatedUserId()).thenReturn(7L);
        when(connection.isOpen()).thenReturn(true);
        when(connection.authorizationRefreshDue(anyLong())).thenReturn(true);
        return connection;
    }

    @Test void oneSlowRevalidationDoesNotPreventAnotherSessionRenewingItsLease() throws Exception {
        var registry = mock(SubscriptionRegistry.class);
        var authenticator = mock(WebSocketAuthenticator.class);
        var slow = connection("slow");
        var healthy = connection("healthy");
        when(registry.connections()).thenReturn(List.of(slow, healthy));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var healthyRenewed = new CountDownLatch(1);
        var slowRenewed = new CountDownLatch(1);
        doAnswer(call -> { healthyRenewed.countDown(); return null; }).when(healthy).renewAuthorization(anyString(), any());
        doAnswer(call -> { slowRenewed.countDown(); return null; }).when(slow).renewAuthorization(anyString(), any());
        var expiry = Instant.now().plusSeconds(60);
        var principal = new JwtPrincipal(7, "qa", "NORMAL", List.of("USER"), expiry);
        when(authenticator.principal("slow", null)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return principal;
        });
        when(authenticator.principal("healthy", null)).thenReturn(principal);
        var authorization = new WebSocketSessionAuthorization(registry, authenticator, Optional.empty());
        var properties = new WebSocketProperties();
        properties.getSecurity().setAuthorizationWorkers(2);
        var executor = new WebSocketAuthorizationConfiguration().websocketAuthorizationChecks(properties);
        try (var task = new WebSocketSessionAuthorizationTask(registry, authorization, executor)) {
            task.revalidateSessions();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(healthyRenewed.await(1, TimeUnit.SECONDS)).isTrue();
            verify(healthy).renewAuthorization("healthy", expiry);
            assertThat(slowRenewed.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(slowRenewed.await(1, TimeUnit.SECONDS)).isTrue();
            verify(slow).renewAuthorization("slow", expiry);
        } finally { release.countDown(); }
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test void saturationIsBoundedAndCannotExtendExpiredSendLeases() throws Exception {
        var registry = mock(SubscriptionRegistry.class);
        var authorization = mock(WebSocketSessionAuthorization.class);
        var clients = List.of(connection("one"), connection("two"), connection("three"));
        when(registry.connections()).thenReturn(clients);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            return null;
        }).when(authorization).revalidate(any());
        var properties = new WebSocketProperties();
        properties.getSecurity().setAuthorizationWorkers(1);
        properties.getSecurity().setAuthorizationQueueCapacity(1);
        var executor = new WebSocketAuthorizationConfiguration().websocketAuthorizationChecks(properties);
        try (var task = new WebSocketSessionAuthorizationTask(registry, authorization, executor)) {
            task.revalidateSessions();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getPoolSize()).isEqualTo(1);
            assertThat(executor.getQueue()).hasSize(1);
            for (var client : clients) when(client.authorizationExpired(anyLong())).thenReturn(true);
            task.revalidateSessions();
            for (var client : clients) {
                verify(client).close(argThat(status -> status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
                verify(client, never()).renewAuthorization(anyString(), any());
            }
            assertThat(executor.getQueue()).hasSize(1);
            release.countDown();
        } finally { release.countDown(); }
        assertThat(executor.isTerminated()).isTrue();
        assertThat(executor.getQueue()).isEmpty();
    }
}
