package com.surprising.gateway.provider.telegram.bot.model;

import java.time.Instant;

public record TelegramSecuritySettings(
        long userId,
        TelegramSecurityMode securityMode,
        boolean hasTradePin,
        String preferredLanguage,
        Instant updatedAt
) {
    public TelegramSecuritySettings(long userId, TelegramSecurityMode securityMode, boolean hasTradePin, Instant updatedAt) {
        this(userId, securityMode, hasTradePin, null, updatedAt);
    }

    public static TelegramSecuritySettings defaultFor(long userId) {
        return new TelegramSecuritySettings(userId, TelegramSecurityMode.CONFIRM_ONLY, false, null, Instant.now());
    }
}
