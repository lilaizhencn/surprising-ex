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
    MATCHES("matches", false),
    POSITIONS("positions", false);

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
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (WsChannel channel : values()) {
            if (channel.code.equals(normalized) || channel.name().equalsIgnoreCase(normalized)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("unsupported websocket channel: " + value);
    }
}
