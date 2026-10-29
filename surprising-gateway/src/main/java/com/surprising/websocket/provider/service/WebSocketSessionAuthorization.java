package com.surprising.websocket.provider.service;

import com.surprising.gateway.c2c.websocket.C2cChatRealtime;
import com.surprising.websocket.provider.session.ClientConnection;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Revalidates a connected trading session; queueing and worker lifecycle belong to the task. */
@Service
public final class WebSocketSessionAuthorization {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WebSocketSessionAuthorization.class);
    private final AtomicLong nextFailureLog = new AtomicLong();
    private final SubscriptionRegistry registry;
    private final WebSocketAuthenticator authenticator;
    private final java.util.Optional<C2cChatRealtime> chat;

    public WebSocketSessionAuthorization(SubscriptionRegistry registry, WebSocketAuthenticator authenticator, java.util.Optional<C2cChatRealtime> chat) {
        this.registry = registry;
        this.authenticator = authenticator;
        this.chat = chat;
    }

    public void revalidate(ClientConnection connection) {
        String token = connection.accessToken();
        String apiKey = connection.apiKey();
        if (!connection.isOpen()) {
            chat.ifPresent(value -> value.closed(connection.id()));
            registry.remove(connection.id());
            return;
        }
        try {
            if (connection.authorizationExpired(System.currentTimeMillis()))
                throw new IllegalArgumentException("websocket authorization expired");
            if (apiKey == null) {
                var principal = authenticator.principal(token);
                if (!Objects.equals(connection.authenticatedUserId(), principal.userId()))
                    throw new IllegalArgumentException("websocket identity mismatch");
                connection.renewAuthorization(token, principal.expiresAt());
            } else {
                long user = authenticator.apiKeyUser(apiKey, connection.clientIp());
                if (!Objects.equals(connection.authenticatedUserId(), user))
                    throw new IllegalArgumentException("websocket identity mismatch");
                connection.renewApiKeyAuthorization(apiKey);
            }
        } catch (RuntimeException failure) {
            synchronized (connection) {
                // A slow result for the previous token must not invalidate a concurrent token rotation.
                if (!Objects.equals(token, connection.accessToken()) || !Objects.equals(apiKey, connection.apiKey())) return;
                connection.close(CloseStatus.POLICY_VIOLATION.withReason("websocket authorization unavailable"));
                chat.ifPresent(value -> value.closed(connection.id()));
                registry.remove(connection.id());
            }
            long now = System.nanoTime();
            long next = nextFailureLog.get();
            if (now >= next && nextFailureLog.compareAndSet(next, now + 5_000_000_000L))
                LOG.warn("ws.authorization.closed session={} error={}", connection.id(), failure.getClass().getSimpleName());
        }
    }
}
