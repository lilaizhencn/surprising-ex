package com.surprising.websocket.provider.service;

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
            List<SubscriptionRegistry.ChannelMetric> channels) {
    }
