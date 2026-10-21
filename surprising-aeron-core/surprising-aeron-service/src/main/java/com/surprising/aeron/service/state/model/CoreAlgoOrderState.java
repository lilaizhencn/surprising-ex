package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.order.OrderReservation;
import java.util.List;

public record CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds,
        long retiredExecutedQuantitySteps, long pendingClosedChildId) {

    /** Initial parent intent has no retired execution or unlinked closed child. */
    public CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds) {
        this(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,priceTicks,quantitySteps,
                childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,reduceOnly,postOnly,
                timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,nextSliceAtEpochMillis,
                completedAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis,revision,childOrderIds,0,0);
    }

    public CoreAlgoOrderState withRetiredProgress(long executed, long pendingChild) {
        return new CoreAlgoOrderState(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,
                priceTicks,quantitySteps,childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,
                reduceOnly,postOnly,timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,
                nextSliceAtEpochMillis,completedAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis,revision,
                childOrderIds,executed,pendingChild);
    }

    public CoreAlgoOrderState {
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        rejectReason = rejectReason == null ? "" : rejectReason;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || algoTypeCode < 0 || side == null || quantitySteps <= 0
                || childQuantitySteps <= 0 || intervalSeconds <= 0 || durationSeconds <= 0 || marginMode == null
                || positionSide == null || timeInForce == null || statusCode < 0 || startAtEpochMillis <= 0
                || createdAtEpochMillis <= 0 || updatedAtEpochMillis <= 0 || revision < 0
                || retiredExecutedQuantitySteps < 0 || retiredExecutedQuantitySteps > quantitySteps || pendingClosedChildId < 0) {
            throw new IllegalArgumentException("invalid algo order state");
        }
        for (int i = 0; i < childOrderIds.size(); i++) {
            Long value = childOrderIds.get(i);
            if (value == null || value <= 0) {
                throw new IllegalArgumentException("invalid algo order state");
            }
        }
    }

    public static CoreAlgoOrderState from(CoreAlgoOrderView value) {
        return new CoreAlgoOrderState(value.algoOrderId(), value.userId(), value.clientAlgoOrderId(), value.instrumentId(),
                value.algoTypeCode(), value.side(), value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(),
                value.intervalSeconds(), value.durationSeconds(), value.marginMode(), value.positionSide(), value.reduceOnly(),
                value.postOnly(), value.timeInForce(), value.statusCode(), value.currentOrderId(), value.rejectReason(),
                value.startAtEpochMillis(), value.nextSliceAtEpochMillis(), value.completedAtEpochMillis(),
                value.createdAtEpochMillis(), value.updatedAtEpochMillis(), value.revision(), value.childOrderIds());
    }

    public boolean terminal() {
        return statusCode == 3 || statusCode == 4 || statusCode == 5;
    }
}
