package com.surprising.websocket.api.model;

import jakarta.validation.constraints.Size;

public record WsClientCommand(
        @Size(max = 32) String op,
        @Size(max = 64) String id,
        @Size(max = 32) String channel,
        @Size(max = 64) String symbol,
        @Size(max = 16) String period,
        Long userId) {
}
