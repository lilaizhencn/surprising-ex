package com.surprising.websocket.provider.service;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/websocket")
public class AdminWebSocketMetricsController {

    private final SubscriptionRegistry registry;

    public AdminWebSocketMetricsController(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public WebSocketAdminMetrics metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername) {
        if (adminUserId == null || adminUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "admin identity header is required");
        }
        return new WebSocketAdminMetrics(
                Instant.now(),
                adminUserId,
                adminUsername,
                registry.activeConnectionCount(),
                registry.authenticatedConnectionCount(),
                registry.anonymousConnectionCount(),
                registry.totalSubscriptionCount(),
                registry.uniqueTopicCount(),
                registry.maxSubscriptionsPerSession(),
                registry.channelMetrics());
    }

    public record WebSocketAdminMetrics(
            Instant generatedAt,
            String adminUserId,
            String adminUsername,
            long activeConnections,
            long authenticatedConnections,
            long anonymousConnections,
            long totalSubscriptions,
            long uniqueTopics,
            long maxSubscriptionsPerSession,
            List<SubscriptionRegistry.ChannelMetric> channels) {
    }
}
