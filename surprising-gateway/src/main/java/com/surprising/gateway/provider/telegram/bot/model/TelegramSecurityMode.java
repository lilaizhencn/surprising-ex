package com.surprising.gateway.provider.telegram.bot.model;

public enum TelegramSecurityMode {
    CONFIRM_ONLY,
    TRADE_PASSWORD,
    OTP_VERIFY;

    public static TelegramSecurityMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return CONFIRM_ONLY;
        }
        try {
            return TelegramSecurityMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CONFIRM_ONLY;
        }
    }
}
