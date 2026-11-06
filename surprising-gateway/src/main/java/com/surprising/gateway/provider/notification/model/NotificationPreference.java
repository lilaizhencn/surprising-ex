package com.surprising.gateway.provider.notification.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 用户通知接收偏好设置模型 */
public record NotificationPreference(
        long userId,
        boolean marketVolatilityPush,
        boolean marketVolatilityInApp,
        BigDecimal marketVolatilityThresholdPct,
        boolean securityLoginPush,
        boolean securityLoginEmail,
        boolean tradingFillPush,
        boolean tradingFillInApp,
        boolean liquidationRiskPush,
        boolean liquidationRiskInApp,
        boolean assetTransferPush,
        boolean assetTransferInApp,
        boolean c2cOrderPush,
        boolean c2cOrderInApp,
        Instant updatedAt
) {
    public static NotificationPreference defaultForUser(long userId) {
        return new NotificationPreference(
                userId,
                true,
                true,
                new BigDecimal("5.00"),
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                Instant.now()
        );
    }
}
