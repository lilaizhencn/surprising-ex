package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import java.util.List;

/** 算法母单的不可变状态，保存总目标、切片计划、子单关系和已经退役子单的成交进度。 */
public record CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds,
        long retiredExecutedQuantitySteps, long pendingClosedChildId) {

    /** 创建初始母单状态，历史退役子单成交量和待处理的已关闭子单编号都设为 0。 */
    public CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds) {
        this(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,priceTicks,quantitySteps,
                childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,reduceOnly,
                timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,nextSliceAtEpochMillis,
                createdAtEpochMillis,updatedAtEpochMillis,revision,childOrderIds,0,0);
    }

    /** 返回更新了退役子单累计成交量和待处理关闭子单编号的新母单，其余执行计划及修订号保持原值。 */
    public CoreAlgoOrderState withRetiredProgress(long executed, long pendingChild) {
        return new CoreAlgoOrderState(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,
                priceTicks,quantitySteps,childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,
                reduceOnly,timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,
                nextSliceAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis,revision,
                childOrderIds,executed,pendingChild);
    }

    public CoreAlgoOrderState {
        if (algoTypeCode == CoreAlgoOrderCommand.TWAP_TYPE_CODE) timeInForce = CoreTimeInForce.IOC;
        if (algoTypeCode == CoreAlgoOrderCommand.ICEBERG_TYPE_CODE) intervalSeconds = 0;
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        instrumentId = InstrumentId.requireValid(instrumentId);
        rejectReason = rejectReason == null ? "" : rejectReason;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || algoTypeCode < 0 || side == null || quantitySteps <= 0
                || childQuantitySteps <= 0
                || algoTypeCode != CoreAlgoOrderCommand.ICEBERG_TYPE_CODE && intervalSeconds <= 0
                || durationSeconds <= 0 || marginMode == null
                || positionSide == null || timeInForce == null || statusCode < 0 || startAtEpochMillis <= 0
                || createdAtEpochMillis <= 0 || updatedAtEpochMillis <= 0 || revision < 0
                || retiredExecutedQuantitySteps < 0 || retiredExecutedQuantitySteps > quantitySteps || pendingClosedChildId < 0) {
            throw new IllegalArgumentException("invalid algo order state");
        }
        for (int i = 0; i < childOrderIds.size(); i++) {
            Long value = childOrderIds.get(i);
            if (value <= 0) {
                throw new IllegalArgumentException("invalid algo order state");
            }
        }
    }

    /** 按协议状态编号判断母单是否结束，3、4、5 都不再作为运行中母单处理。 */
    public boolean terminal() {
        return statusCode == 3 || statusCode == 4 || statusCode == 5;
    }
}
