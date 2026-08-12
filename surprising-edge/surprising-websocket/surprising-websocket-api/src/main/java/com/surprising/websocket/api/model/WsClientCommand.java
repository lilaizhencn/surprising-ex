package com.surprising.websocket.api.model;

import jakarta.validation.constraints.Size;

public record WsClientCommand(
        @Size(max = 32) String op,
        @Size(max = 64) String id,
        @Size(max = 32) String channel,
        @Size(max = 64) String symbol,
        @Size(max = 16) String period,
        Long userId,
        @Size(max = 32) String productLine,
        @Size(max = 4096) String token) {

    public WsClientCommand(String op,
                           String id,
                           String channel,
                           String symbol,
                           String period,
                           Long userId) {
        this(op, id, channel, symbol, period, userId, null, null);
    }

    public WsClientCommand(String op,
                           String id,
                           String channel,
                           String symbol,
                           String period,
                           Long userId,
                           String productLine) {
        this(op, id, channel, symbol, period, userId, productLine, null);
    }
}
