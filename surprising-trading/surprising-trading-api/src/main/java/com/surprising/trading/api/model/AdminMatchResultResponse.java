package com.surprising.trading.api.model;

import java.time.Instant;

public record AdminMatchResultResponse(
        long commandId,
        long orderId,
        long userId,
        String symbol,
        long instrumentVersion,
        OrderCommandType commandType,
        String resultCode,
        long filledQuantitySteps,
        OrderStatus orderStatus,
        String traceId,
        Instant eventTime,
        Instant createdAt) {
}
