package com.surprising.websocket.provider.task;

import com.surprising.websocket.provider.service.SubscriptionRegistry;
import com.surprising.websocket.provider.service.WebSocketSessionAuthorization;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded, isolated authorization workers keep a slow database call off the shared scheduler. */
@Component
public final class WebSocketSessionAuthorizationTask implements AutoCloseable {
    private final SubscriptionRegistry registry;
    private final WebSocketSessionAuthorization authorization;
    private final ThreadPoolExecutor checks;
    private final Set<String> checking = ConcurrentHashMap.newKeySet();

    public WebSocketSessionAuthorizationTask(SubscriptionRegistry registry,
            WebSocketSessionAuthorization authorization,
            @org.springframework.beans.factory.annotation.Qualifier("websocketAuthorizationChecks") ThreadPoolExecutor checks) {
        this.registry = registry;
        this.authorization = authorization;
        this.checks = checks;
    }

    @Scheduled(fixedDelay = 1000)
    public void revalidateSessions() {
        if (checks.isShutdown()) return;
        long now = System.currentTimeMillis();
        long monotonicNow = System.nanoTime();
        for (var connection : registry.connections()) {
            if (connection.limitLeaseExpired(monotonicNow))
                connection.close(CloseStatus.SERVICE_OVERLOAD.withReason("websocket limit lease expired"));
            // Expiry is enforced even if every database worker is currently blocked.
            if (connection.authorizationExpired(now))
                connection.close(CloseStatus.POLICY_VIOLATION.withReason("websocket authorization expired"));
            if (connection.isOpen() && !connection.authorizationRefreshDue(now)) continue;
            if (!checking.add(connection.id())) continue;
            try {
                checks.execute(() -> {
                    try { authorization.revalidate(connection); }
                    finally { checking.remove(connection.id()); }
                });
            } catch (RejectedExecutionException saturated) {
                checking.remove(connection.id()); // Retry next tick; the send lease never extends here.
            }
        }
    }

    @PreDestroy
    @Override public void close() {
        checks.shutdownNow();
        checking.clear();
        try {
            if (!checks.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("websocket authorization workers did not terminate");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping websocket authorization workers", failure);
        }
    }
}
