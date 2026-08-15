package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;

public record CoreTriggerOrderStateView(
        long triggerOrderId,
        ProductLine productLine,
        long userId,
        String clientTriggerOrderId,
        String ocoGroupId,
        String symbol,
        CoreOrderSide side,
        CoreTriggerOrderType triggerType,
        CoreTriggerCondition triggerCondition,
        long triggerPriceTicks,
        long activationPriceTicks,
        long callbackRatePpm,
        long highestPriceTicks,
        long lowestPriceTicks,
        long activatedAtEpochMillis,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        long priceTicks,
        long quantitySteps,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreTriggerOrderStatus status,
        long placedOrderId,
        long triggerSequence,
        long triggeredPriceTicks,
        String rejectReason,
        String traceId,
        long expiresAtEpochMillis,
        long triggeredAtEpochMillis,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long revision,
        long instrumentVersion,
        long makerFeeRatePpm,
        long takerFeeRatePpm) {

    public CoreTriggerOrderStateView(long triggerOrderId, ProductLine productLine, long userId,
                                     String clientTriggerOrderId, String ocoGroupId, String symbol,
                                     CoreOrderSide side, CoreTriggerOrderType triggerType,
                                     CoreTriggerCondition triggerCondition, long triggerPriceTicks,
                                     long activationPriceTicks, long callbackRatePpm, long highestPriceTicks,
                                     long lowestPriceTicks, long activatedAtEpochMillis, CoreOrderType orderType,
                                     CoreTimeInForce timeInForce, long priceTicks, long quantitySteps,
                                     CoreMarginMode marginMode, CorePositionSide positionSide,
                                     CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                     long triggeredPriceTicks, String rejectReason, String traceId,
                                     long expiresAtEpochMillis, long triggeredAtEpochMillis,
                                     long createdAtEpochMillis, long updatedAtEpochMillis, long revision) {
        this(triggerOrderId, productLine, userId, clientTriggerOrderId, ocoGroupId, symbol, side, triggerType,
                triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm, highestPriceTicks,
                lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, traceId, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision, 0, 0, 0);
    }

    public CoreTriggerOrderStateView {
        clientTriggerOrderId = clientTriggerOrderId == null ? "" : clientTriggerOrderId;
        ocoGroupId = ocoGroupId == null ? "" : ocoGroupId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        traceId = traceId == null ? "" : traceId;
        if (triggerOrderId <= 0 || productLine == null || userId <= 0 || symbol == null || symbol.isBlank()
                || side == null || triggerType == null || triggerCondition == null || triggerPriceTicks < 0
                || callbackRatePpm < 0 || orderType == null || timeInForce == null || quantitySteps <= 0
                || marginMode == null || positionSide == null || status == null || revision < 0
                || instrumentVersion < 0 || makerFeeRatePpm < -1_000_000L || makerFeeRatePpm > 1_000_000L
                || takerFeeRatePpm < -1_000_000L || takerFeeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException("invalid trigger order state");
        }
    }
}
