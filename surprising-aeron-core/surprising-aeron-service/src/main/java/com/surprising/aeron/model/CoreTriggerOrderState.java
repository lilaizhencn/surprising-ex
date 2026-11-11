package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreTriggerPriceSource;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;

/** 条件单和追踪止损的不可变业务记录，保存触发条件、价格来源、追踪极值及触发后的普通订单结果。 */
public record CoreTriggerOrderState(
        long triggerOrderId, long userId, String clientTriggerOrderId, String ocoGroupId,
        CoreInstrument instrument, CoreOrderSide side, CoreTriggerOrderType triggerType,
        CoreTriggerCondition triggerCondition,
        long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm, long highestPriceTicks,
        long lowestPriceTicks, long activatedAtEpochMillis, CoreOrderType orderType, CoreTimeInForce timeInForce,
        long priceTicks, long quantitySteps, CoreMarginMode marginMode, CorePositionSide positionSide,
        CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence, long triggeredPriceTicks,
        String rejectReason, long expiresAtEpochMillis, long triggeredAtEpochMillis,
        long createdAtEpochMillis, long updatedAtEpochMillis, long revision,
        CoreTriggerPriceSource priceSource) {

    /** 创建以标记价判断条件的触发单。 */
    public CoreTriggerOrderState(long triggerOrderId, long userId,
                                 String clientTriggerOrderId, String ocoGroupId,
                                 CoreInstrument instrument, CoreOrderSide side,
                                 CoreTriggerOrderType triggerType, CoreTriggerCondition triggerCondition,
                                 long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm,
                                 long highestPriceTicks, long lowestPriceTicks, long activatedAtEpochMillis,
                                 CoreOrderType orderType, CoreTimeInForce timeInForce, long priceTicks,
                                 long quantitySteps, CoreMarginMode marginMode, CorePositionSide positionSide,
                                 CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                 long triggeredPriceTicks, String rejectReason,
                                 long expiresAtEpochMillis, long triggeredAtEpochMillis, long createdAtEpochMillis,
                                 long updatedAtEpochMillis, long revision) {
        this(triggerOrderId, userId, clientTriggerOrderId, ocoGroupId, instrument, side, triggerType,
                triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm, highestPriceTicks,
                lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision, CoreTriggerPriceSource.MARK);
    }

    public CoreTriggerOrderState {
        clientTriggerOrderId = clientTriggerOrderId == null ? "" : clientTriggerOrderId;
        ocoGroupId = ocoGroupId == null ? "" : ocoGroupId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        if (triggerOrderId <= 0 || userId <= 0 || instrument == null
                || side == null || triggerType == null || triggerCondition == null || triggerPriceTicks < 0
                || callbackRatePpm < 0 || orderType == null || timeInForce == null || quantitySteps <= 0
                || marginMode == null || positionSide == null || status == null || priceSource == null || revision < 0) {
            throw new IllegalArgumentException("invalid trigger order state");
        }
    }

    /** 标的固定编号由绑定的权威标的提供。 */
    public String instrumentId() {
        return instrument.instrumentId();
    }

    /** 标的的产品归属固定，配置更新不改变合约类型。 */
    public ProductLine productLine() {
        return instrument.contractType().productLine();
    }

    /** 生成协议层的条件单只读视图，保留触发结果和费用快照，不把内部币对配置对象放进视图。 */
    public CoreTriggerOrderStateView view() {
        return new CoreTriggerOrderStateView(triggerOrderId, productLine(), userId, clientTriggerOrderId, ocoGroupId,
                instrumentId(), side, triggerType, triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm,
                highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks,
                quantitySteps, marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision, priceSource);
    }

    /** 从协议视图恢复条件单，并补入与编号一致的币对配置，重新经过构造器校验。 */
    public static CoreTriggerOrderState from(CoreTriggerOrderStateView view, CoreInstrument instrument) {
        if (view == null || instrument == null || !instrument.instrumentId().equals(view.instrumentId())
                || instrument.contractType().productLine() != view.productLine()) {
            throw new IllegalArgumentException("trigger order instrument mismatch");
        }
        return new CoreTriggerOrderState(view.triggerOrderId(), view.userId(),
                view.clientTriggerOrderId(), view.ocoGroupId(), instrument, view.side(), view.triggerType(),
                view.triggerCondition(), view.triggerPriceTicks(), view.activationPriceTicks(), view.callbackRatePpm(),
                view.highestPriceTicks(), view.lowestPriceTicks(), view.activatedAtEpochMillis(), view.orderType(),
                view.timeInForce(), view.priceTicks(), view.quantitySteps(), view.marginMode(), view.positionSide(),
                view.status(), view.placedOrderId(), view.triggerSequence(), view.triggeredPriceTicks(),
                view.rejectReason(), view.expiresAtEpochMillis(), view.triggeredAtEpochMillis(),
                view.createdAtEpochMillis(), view.updatedAtEpochMillis(), view.revision(), view.priceSource());
    }

    /** 要求当前仍待触发，构造记录版本加一的取消后状态。 */
    public CoreTriggerOrderState toPendingCancellation() {
        if (status != CoreTriggerOrderStatus.PENDING) {
            throw new IllegalStateException("closing trigger must be pending");
        }
        return transition(CoreTriggerOrderStatus.CANCELED, 0, triggerSequence,
                triggeredPriceTicks, rejectReason, updatedAtEpochMillis);
    }

    /** 要求正在触发且子订单编号为正，构造触发成功后状态并清拒绝原因。 */
    public CoreTriggerOrderState toMatchedCompletion(long placedOrderId, long updatedAt) {
        if (status != CoreTriggerOrderStatus.TRIGGERING || placedOrderId <= 0) {
            throw new IllegalStateException("invalid matched trigger completion");
        }
        return transition(CoreTriggerOrderStatus.TRIGGERED, placedOrderId,
                triggerSequence, triggeredPriceTicks, "", updatedAt);
    }

    /** 要求正在触发，构造带拒绝原因且无子单编号的失败后状态。 */
    public CoreTriggerOrderState toRejectedCompletion(String reason, long updatedAt) {
        if (status != CoreTriggerOrderStatus.TRIGGERING) {
            throw new IllegalStateException("invalid rejected trigger completion");
        }
        return transition(CoreTriggerOrderStatus.TRIGGER_FAILED, 0,
                triggerSequence, triggeredPriceTicks, reason, updatedAt);
    }

    /** 复制原条件单意图、追踪信息和费率，只替换指定生命周期字段并将记录版本加一。 */
    public CoreTriggerOrderState transition(CoreTriggerOrderStatus nextStatus, long nextPlacedOrderId,
                                           long nextTriggerSequence, long nextTriggeredPriceTicks,
                                           String nextRejectReason, long nextUpdatedAt) {
        return new CoreTriggerOrderState(triggerOrderId, userId, clientTriggerOrderId, ocoGroupId,
                instrument, side, triggerType, triggerCondition, triggerPriceTicks,
                activationPriceTicks, callbackRatePpm, highestPriceTicks, lowestPriceTicks,
                activatedAtEpochMillis, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, nextStatus, nextPlacedOrderId, nextTriggerSequence,
                nextTriggeredPriceTicks, nextRejectReason, expiresAtEpochMillis,
                nextStatus == CoreTriggerOrderStatus.TRIGGERED || nextStatus == CoreTriggerOrderStatus.TRIGGER_FAILED
                        ? nextUpdatedAt : triggeredAtEpochMillis,
                createdAtEpochMillis, nextUpdatedAt, Math.incrementExact(revision), priceSource);
    }

}
