package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.product.api.ProductLine;

public record CoreTriggerOrderState(
        long triggerOrderId, ProductLine productLine, long userId, String clientTriggerOrderId, String ocoGroupId,
        String symbol, CoreOrderSide side, CoreTriggerOrderType triggerType, CoreTriggerCondition triggerCondition,
        long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm, long highestPriceTicks,
        long lowestPriceTicks, long activatedAtEpochMillis, CoreOrderType orderType, CoreTimeInForce timeInForce,
        long priceTicks, long quantitySteps, CoreMarginMode marginMode, CorePositionSide positionSide,
        CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence, long triggeredPriceTicks,
        String rejectReason, String traceId, long expiresAtEpochMillis, long triggeredAtEpochMillis,
        long createdAtEpochMillis, long updatedAtEpochMillis, long revision) {

    public CoreTriggerOrderState {
        clientTriggerOrderId = clientTriggerOrderId == null ? "" : clientTriggerOrderId;
        ocoGroupId = ocoGroupId == null ? "" : ocoGroupId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        traceId = traceId == null ? "" : traceId;
        if (triggerOrderId <= 0 || productLine == null || userId <= 0 || symbol == null || symbol.isBlank()
                || side == null || triggerType == null || triggerCondition == null || triggerPriceTicks < 0
                || callbackRatePpm < 0 || orderType == null || timeInForce == null || quantitySteps <= 0
                || marginMode == null || positionSide == null || status == null || revision < 0) {
            throw new IllegalArgumentException("invalid trigger order state");
        }
    }

    public CoreTriggerOrderStateView view() {
        return new CoreTriggerOrderStateView(triggerOrderId, productLine, userId, clientTriggerOrderId, ocoGroupId,
                symbol, side, triggerType, triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm,
                highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks,
                quantitySteps, marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, traceId, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision);
    }

    public static CoreTriggerOrderState from(CoreTriggerOrderStateView view) {
        return new CoreTriggerOrderState(view.triggerOrderId(), view.productLine(), view.userId(),
                view.clientTriggerOrderId(), view.ocoGroupId(), view.symbol(), view.side(), view.triggerType(),
                view.triggerCondition(), view.triggerPriceTicks(), view.activationPriceTicks(), view.callbackRatePpm(),
                view.highestPriceTicks(), view.lowestPriceTicks(), view.activatedAtEpochMillis(), view.orderType(),
                view.timeInForce(), view.priceTicks(), view.quantitySteps(), view.marginMode(), view.positionSide(),
                view.status(), view.placedOrderId(), view.triggerSequence(), view.triggeredPriceTicks(),
                view.rejectReason(), view.traceId(), view.expiresAtEpochMillis(), view.triggeredAtEpochMillis(),
                view.createdAtEpochMillis(), view.updatedAtEpochMillis(), view.revision());
    }
}
