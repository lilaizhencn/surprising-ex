package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

/** 创建触发单的条件与执行意图。归属、生命周期、时间和费率由 Core 确定。 */
public record PlaceTriggerOrderCommand(
        long triggerOrderId, String clientTriggerOrderId, String ocoGroupId, String instrumentId,
        CoreOrderSide side, CoreTriggerOrderType triggerType, CoreTriggerCondition triggerCondition,
        long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm,
        CoreOrderType orderType, CoreTimeInForce timeInForce, long priceTicks, long quantitySteps,
        CoreMarginMode marginMode, CorePositionSide positionSide, long expiresAtEpochMillis,
        CoreTriggerPriceSource priceSource) {

    public PlaceTriggerOrderCommand {
        clientTriggerOrderId = clientTriggerOrderId == null ? "" : clientTriggerOrderId;
        ocoGroupId = ocoGroupId == null ? "" : ocoGroupId;
        if (triggerOrderId <= 0 || instrumentId == null || instrumentId.isBlank() || side == null
                || triggerType == null || triggerCondition == null || triggerPriceTicks < 0
                || activationPriceTicks < 0 || callbackRatePpm < 0 || orderType == null || timeInForce == null
                || priceTicks < 0 || quantitySteps <= 0 || marginMode == null || positionSide == null
                || expiresAtEpochMillis < 0 || priceSource == null) {
            throw new IllegalArgumentException("invalid trigger placement");
        }
    }

    public CoreTriggerOrderStateView materialize(ProductLine productLine, long userId, long clusterTimestamp) {
        if (clusterTimestamp <= 0) throw new IllegalArgumentException("clusterTimestamp must be positive");
        return new CoreTriggerOrderStateView(triggerOrderId, productLine, userId, clientTriggerOrderId,
                ocoGroupId, instrumentId, side, triggerType, triggerCondition, triggerPriceTicks,
                activationPriceTicks, callbackRatePpm, 0, 0, 0, orderType, timeInForce, priceTicks,
                quantitySteps, marginMode, positionSide, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "",
                expiresAtEpochMillis, 0, clusterTimestamp, clusterTimestamp, 1, 0, 0, priceSource);
    }
}
