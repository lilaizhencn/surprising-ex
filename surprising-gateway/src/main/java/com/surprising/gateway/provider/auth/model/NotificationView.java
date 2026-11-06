package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

/** 用户站内通知的原生查询结果，携带深链接路由与上下文参数。 */
public record NotificationView(long notificationId, String category, String title, String body,
                               Instant readAt, Instant createdAt, String route, String routeParams) {
    public NotificationView(long notificationId, String category, String title, String body,
                            Instant readAt, Instant createdAt) {
        this(notificationId, category, title, body, readAt, createdAt, null, null);
    }
}
