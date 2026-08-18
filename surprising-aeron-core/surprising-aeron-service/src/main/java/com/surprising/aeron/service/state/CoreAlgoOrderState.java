package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import java.util.List;

public record CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String symbol, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, String traceId, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds) {

    public CoreAlgoOrderState {
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        symbol = OrderReservation.normalizeSymbol(symbol);
        rejectReason = rejectReason == null ? "" : rejectReason;
        traceId = traceId == null ? "" : traceId;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || algoTypeCode < 0 || side == null || quantitySteps <= 0
                || childQuantitySteps <= 0 || intervalSeconds <= 0 || durationSeconds <= 0 || marginMode == null
                || positionSide == null || timeInForce == null || statusCode < 0 || startAtEpochMillis <= 0
                || createdAtEpochMillis <= 0 || updatedAtEpochMillis <= 0 || revision < 0
                || childOrderIds.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("invalid algo order state");
        }
    }

    static CoreAlgoOrderState from(CoreAlgoOrderView value) {
        return new CoreAlgoOrderState(value.algoOrderId(), value.userId(), value.clientAlgoOrderId(), value.symbol(),
                value.algoTypeCode(), value.side(), value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(),
                value.intervalSeconds(), value.durationSeconds(), value.marginMode(), value.positionSide(), value.reduceOnly(),
                value.postOnly(), value.timeInForce(), value.statusCode(), value.currentOrderId(), value.rejectReason(),
                value.traceId(), value.startAtEpochMillis(), value.nextSliceAtEpochMillis(), value.completedAtEpochMillis(),
                value.createdAtEpochMillis(), value.updatedAtEpochMillis(), value.revision(), value.childOrderIds());
    }

    public boolean terminal() {
        return statusCode == 3 || statusCode == 4 || statusCode == 5;
    }
}
