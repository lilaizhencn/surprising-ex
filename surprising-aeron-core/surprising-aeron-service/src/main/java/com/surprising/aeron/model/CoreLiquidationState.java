package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;

/** 一笔强平从风险计划、撤单处理到执行及补亏的不可变记录。 */
public record CoreLiquidationState(
        long liquidationId,
        long userId,
        String instrumentId,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        long triggerPriceSequence,
        long signedQuantitySteps,
        long closeQuantitySteps,
        long deficitUnits,
        long executionPriceTicks,
        long liquidationFeeRatePpm,
        long liquidationFeeUnits,
        Status status,
        long nextCancelOrderId) {

    /** 未执行的计划与取消状态不能带执行费用，撤单游标只允许在 ORDERED 保存。 */
    public CoreLiquidationState {
        instrumentId = InstrumentId.requireValid(instrumentId);
        if (liquidationId <= 0 || userId <= 0 || marginMode == null || positionSide == null
                || triggerPriceSequence <= 0 || signedQuantitySteps == 0 || closeQuantitySteps <= 0
                || closeQuantitySteps > Math.absExact(signedQuantitySteps) || deficitUnits < 0
                || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0
                || status == Status.COMPLETED && deficitUnits != 0
                || (status == Status.PLANNED || status == Status.CANCELED)
                && (executionPriceTicks != 0 || liquidationFeeRatePpm != 0 || liquidationFeeUnits != 0)
                || (status != Status.ORDERED && nextCancelOrderId != 0)) {
            throw new IllegalArgumentException("invalid liquidation state");
        }
    }

    /** 创建已提供执行结果的强平记录，撤单继续游标设为 0。 */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId, CoreMarginMode marginMode,
                                CorePositionSide positionSide, long triggerPriceSequence,
                                long signedQuantitySteps, long closeQuantitySteps, long deficitUnits,
                                long executionPriceTicks, long liquidationFeeRatePpm, long liquidationFeeUnits,
                                Status status) {
        this(liquidationId, userId, instrumentId, marginMode, positionSide, triggerPriceSequence,
                signedQuantitySteps, closeQuantitySteps, deficitUnits, executionPriceTicks, liquidationFeeRatePpm,
                liquidationFeeUnits, status, 0);
    }

    /** 创建全仓净持仓强平记录，传入的平仓绝对数量同时作为原持仓的正向数量。 */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId,
                                long triggerPriceSequence, long closeQuantitySteps, long deficitUnits,
                                Status status) {
        this(liquidationId, userId, instrumentId, CoreMarginMode.CROSS, CorePositionSide.NET,
                triggerPriceSequence, closeQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
    }

    /** 强平流程的业务阶段。有剩余亏空时，平仓执行完也不能算整个流程结束。 */
    public enum Status {
        /** 风险扫描已经形成强平计划，尚未进入执行阶段。 */
        PLANNED,
        /** 强平已进入执行前的撤单等处理阶段，可保存下一笔撤单编号。 */
        ORDERED,
        /** 强平执行及亏空覆盖已经完成，剩余亏空必须为 0。 */
        COMPLETED,
        /** 执行后仍有亏空，需要保险基金覆盖。 */
        INSURANCE_REQUIRED,
        /** 剩余亏空需要进入自动减仓处理。 */
        ADL_REQUIRED,
        /** 尚未执行的强平计划已取消，不再继续执行。 */
        CANCELED
    }

    /** 已取消或已完成是强平终态。 */
    public boolean terminal() {
        return status == Status.CANCELED || status == Status.COMPLETED;
    }

    /** 记录已进入 ORDERED 阶段及下一笔待撤订单，不在这里发出真实撤单命令。 */
    public CoreLiquidationState ordered(long nextOrderId) {
        if (nextOrderId <= 0) throw new IllegalArgumentException("next cancellation order id must be positive");
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                Status.ORDERED, nextOrderId);
    }

}
