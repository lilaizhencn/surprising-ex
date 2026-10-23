package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.order.state.OrderReservation;

/**
 * 一笔强平从风险计划、撤单处理到执行及补亏的不可变记录。保存原持仓方向、计划平仓量、费用和剩余亏空；状态方法只生成新记录，不直接操作账户。
 * @param liquidationId 这次强平流程的唯一编号
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
 * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
 * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
 * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
 * @param executionPriceTicks 记录的强平执行价格 tick，尚未执行时为 0
 * @param liquidationFeeRatePpm 这次强平执行采用的费用比例，单位为百万分之一
 * @param liquidationFeeUnits 这次强平执行记录的费用金额，单位为结算资产最小单位
 * @param status 强平流程当前阶段，执行后有亏空时还要经过保险基金或 ADL 处理
 * @param nextCancelOrderId 强平撤单阶段下一笔待处理的订单编号，非该阶段必须为 0
 */
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

    /**
     * 检查强平数量、费用和亏空等范围，要求完成状态没有亏空；未执行的计划与取消状态不能带执行费用，撤单游标只允许在 ORDERED 保存。
     */
    public CoreLiquidationState {
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
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

    /**
     * 创建已提供执行结果的强平记录，撤单继续游标设为 0。
     * @param liquidationId 这次强平流程的唯一编号
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
     * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
     * @param executionPriceTicks 记录的强平执行价格 tick，尚未执行时为 0
     * @param liquidationFeeRatePpm 这次强平执行采用的费用比例，单位为百万分之一
     * @param liquidationFeeUnits 这次强平执行记录的费用金额，单位为结算资产最小单位
     * @param status 强平流程当前阶段，执行后有亏空时还要经过保险基金或 ADL 处理
     */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId, CoreMarginMode marginMode,
                                CorePositionSide positionSide, long triggerPriceSequence,
                                long signedQuantitySteps, long closeQuantitySteps, long deficitUnits,
                                long executionPriceTicks, long liquidationFeeRatePpm, long liquidationFeeUnits,
                                Status status) {
        this(liquidationId, userId, instrumentId, marginMode, positionSide, triggerPriceSequence,
                signedQuantitySteps, closeQuantitySteps, deficitUnits, executionPriceTicks, liquidationFeeRatePpm,
                liquidationFeeUnits, status, 0);
    }

    /**
     * 创建全仓净持仓强平记录，传入的平仓绝对数量同时作为原持仓的正向数量。
     * @param liquidationId 这次强平流程的唯一编号
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
     * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
     * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
     * @param status 强平流程当前阶段，执行后有亏空时还要经过保险基金或 ADL 处理
     */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId,
                                long triggerPriceSequence, long closeQuantitySteps, long deficitUnits,
                                Status status) {
        this(liquidationId, userId, instrumentId, CoreMarginMode.CROSS, CorePositionSide.NET,
                triggerPriceSequence, closeQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
    }

    /**
     * 强平流程的业务阶段；有剩余亏空时，平仓执行完也不能算整个流程结束。
     */
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

    /**
     * 已取消或已完成是强平终态；构造器保证完成态亏空为 0，待保险基金和 ADL 的记录仍需继续处理。
     */
    public boolean terminal() {
        return status == Status.CANCELED || status == Status.COMPLETED;
    }

    /**
     * 记录已进入 ORDERED 阶段及下一笔待撤订单，不在这里发出真实撤单命令。
     * @param nextOrderId 强平撤单阶段下一笔待撤订单编号，必须为正数
     */
    public CoreLiquidationState ordered(long nextOrderId) {
        if (nextOrderId <= 0) throw new IllegalArgumentException("next cancellation order id must be positive");
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                deficitUnits, executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits,
                Status.ORDERED, nextOrderId);
    }

}
