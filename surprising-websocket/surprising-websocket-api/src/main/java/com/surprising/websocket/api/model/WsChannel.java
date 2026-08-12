package com.surprising.websocket.api.model;

import java.util.Locale;

public enum WsChannel {
    CANDLES("candles", true),
    TRADES("trades", true),
    ORDER_BOOK_DEPTH("depth", true),
    INDEX_PRICE("index", true),
    MARK_PRICE("mark", true),
    FUNDING_RATE("funding", true),
    ORDERS("orders", false),
    TRIGGER_ORDERS("triggerOrders", false),
    MATCHES("matches", false),
    EXECUTION_REPORTS("executionReports", false),
    POSITIONS("positions", false),
    ACCOUNT_RISK("accountRisk", false),
    POSITION_RISK("positionRisk", false);

    private final String code;
    private final boolean publicChannel;

    WsChannel(String code, boolean publicChannel) {
        this.code = code;
        this.publicChannel = publicChannel;
    }

    public String code() {
        return code;
    }

    public boolean isPublicChannel() {
        return publicChannel;
    }

    public static WsChannel fromCode(String value) {
        String trimmed = value == null ? "" : value.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        for (WsChannel channel : values()) {
            String compactName = channel.name().replace("_", "");
            if (channel.code.equalsIgnoreCase(trimmed)
                    || channel.name().equalsIgnoreCase(trimmed)
                    || compactName.equalsIgnoreCase(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("unsupported websocket channel: " + value);
    }
}
