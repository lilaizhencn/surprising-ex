package com.surprising.websocket.provider.model;

import java.time.Instant;
import java.util.List;

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
            List<ChannelMetric> channels) {
    }
