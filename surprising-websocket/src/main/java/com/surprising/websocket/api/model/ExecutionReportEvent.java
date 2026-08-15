package com.surprising.websocket.api.model;

import java.time.Instant;

public record ExecutionReportEvent(
        String reportType,
        long userId,
        String symbol,
        Long orderId,
        Long commandId,
        Long tradeId,
        Long counterpartyOrderId,
        Long counterpartyUserId,
        Long instrumentVersion,
        String orderEventType,
        String commandType,
        String orderStatus,
        String resultCode,
        String liquidityRole,
        String side,
        String marginMode,
        String positionSide,
        Long priceTicks,
        Long quantitySteps,
        Long filledQuantitySteps,
        Boolean orderCompleted,
        String reason,
        String traceId,
        Instant eventTime) {
}
