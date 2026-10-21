package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

/** 用户站内通知的原生查询结果，字段保持公开响应契约。 */
public record NotificationView(long notificationId, String category, String title, String body,
                               Instant readAt, Instant createdAt) {}
