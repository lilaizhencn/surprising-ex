package com.surprising.aeron.protocol;

import java.util.List;

public record CoreAlgoOrderView(
        long algoOrderId, long userId, String clientAlgoOrderId, String symbol, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, String traceId, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds,
        long executedQuantitySteps, long activeQuantitySteps, int activeChildOrderCount) {

    public CoreAlgoOrderView {
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        traceId = traceId == null ? "" : traceId;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || symbol == null || symbol.isBlank() || algoTypeCode < 0
                || side == null || quantitySteps <= 0 || childQuantitySteps <= 0 || intervalSeconds <= 0
                || durationSeconds <= 0 || marginMode == null || positionSide == null || timeInForce == null
                || statusCode < 0 || startAtEpochMillis <= 0 || createdAtEpochMillis <= 0
                || updatedAtEpochMillis <= 0 || revision < 0 || executedQuantitySteps < 0
                || activeQuantitySteps < 0 || activeChildOrderCount < 0) {
            throw new IllegalArgumentException("invalid algo order view");
        }
    }
}
