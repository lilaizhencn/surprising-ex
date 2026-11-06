package com.surprising.gateway.provider.notification.model;

import java.time.Instant;

/** 客户端推送设备标识与环境配置 */
public record UserDeviceToken(
        long tokenId,
        long userId,
        String deviceToken,
        String platform,
        String deviceName,
        String locale,
        boolean enabled,
        Instant lastActiveAt,
        Instant createdAt,
        Instant updatedAt
) {}
