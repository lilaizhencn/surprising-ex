package com.surprising.aeron.protocol;

import java.util.List;

/**
 * 父单查询事实：childOrderIds 只包含已经关联的子单。
 * pendingClosedChildId 是已退休但尚未关联的子单，其成交量已计入 executedQuantitySteps。
 */
public record CoreAlgoOrderView(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds,
        long executedQuantitySteps, long activeQuantitySteps, int activeChildOrderCount,
        long pendingClosedChildId) {

    public CoreAlgoOrderView {
        if (algoTypeCode == CoreAlgoOrderCommand.TWAP_TYPE_CODE) timeInForce = CoreTimeInForce.IOC;
        if (algoTypeCode == CoreAlgoOrderCommand.ICEBERG_TYPE_CODE) intervalSeconds = 0;
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || instrumentId == null || instrumentId.isBlank() || algoTypeCode < 0
                || side == null || quantitySteps <= 0 || childQuantitySteps <= 0
                || algoTypeCode != CoreAlgoOrderCommand.ICEBERG_TYPE_CODE && intervalSeconds <= 0
                || durationSeconds <= 0 || marginMode == null || positionSide == null || timeInForce == null
                || statusCode < 0 || startAtEpochMillis <= 0 || createdAtEpochMillis <= 0
                || updatedAtEpochMillis <= 0 || revision < 0 || executedQuantitySteps < 0
                || activeQuantitySteps < 0 || activeChildOrderCount < 0 || pendingClosedChildId < 0) {
            throw new IllegalArgumentException("invalid algo order view");
        }
    }
}
