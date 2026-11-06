package com.surprising.gateway.provider.notification.model;

import java.time.Instant;
import java.util.UUID;

/** 系统推送出件箱条目 */
public record PushOutboxItem(
        UUID pushId,
        long userId,
        String deviceToken,
        String platform,
        String category,
        String title,
        String body,
        String route,
        String routeParams,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        String idempotencyKey,
        Instant createdAt,
        Instant sentAt
) {}
