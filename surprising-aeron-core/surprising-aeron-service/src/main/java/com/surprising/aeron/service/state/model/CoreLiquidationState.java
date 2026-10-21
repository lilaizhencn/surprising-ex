package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.order.OrderReservation;

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
     * 创建全仓强平记录，保留明确传入的持仓腿及带方向数量，执行价格和费用从 0 开始。
     * @param liquidationId 这次强平流程的唯一编号
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
     * @param signedQuantitySteps 带方向的持仓数量步数，正数为多头、负数为空头、0 为无持仓
     * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
     * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
     * @param status 强平流程当前阶段，执行后有亏空时还要经过保险基金或 ADL 处理
     */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId, CorePositionSide positionSide,
                                long triggerPriceSequence, long signedQuantitySteps,
                                long closeQuantitySteps, long deficitUnits, Status status) {
        this(liquidationId, userId, instrumentId, CoreMarginMode.CROSS, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
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
     * 按持仓腿创建全仓强平记录，空头腿使用负的原持仓数量，其他腿使用正数量。
     * @param liquidationId 这次强平流程的唯一编号
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
     * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
     * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
     * @param status 强平流程当前阶段，执行后有亏空时还要经过保险基金或 ADL 处理
     */
    public CoreLiquidationState(long liquidationId, long userId, String instrumentId, CorePositionSide positionSide,
                                long triggerPriceSequence, long closeQuantitySteps,
                                long deficitUnits, Status status) {
        this(liquidationId, userId, instrumentId, CoreMarginMode.CROSS, positionSide, triggerPriceSequence,
                positionSide == CorePositionSide.SHORT ? Math.negateExact(closeQuantitySteps) : closeQuantitySteps,
                closeQuantitySteps, deficitUnits, 0, 0, 0, status, 0);
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
     * 只有已取消或已完成且亏空为 0 才是强平终态；待保险基金和 ADL 的记录仍需继续处理。
     */
    public boolean terminal() {
        return status == Status.CANCELED || status == Status.COMPLETED && deficitUnits == 0;
    }

    /**
     * 复制为调用方指定的阶段；离开 ORDERED 时清掉撤单游标，其他字段仍由构造器校验。
     * @param nextStatus 调用方决定的下一状态，这里只构造并校验新记录
     */
    public CoreLiquidationState withStatus(Status nextStatus) {
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, deficitUnits,
                executionPriceTicks, liquidationFeeRatePpm, liquidationFeeUnits, nextStatus,
                nextStatus == Status.ORDERED ? nextCancelOrderId : 0);
    }

    /**
     * 仅刷新尚处于计划阶段的强平，按最新持仓重设全部平仓量并清零执行和亏空信息。
     * @param nextMarginMode 重新评估强平计划时的最新全仓或逐仓模式
     * @param nextPriceSequence 重新评估强平计划采用的新行情序号
     * @param nextSignedQuantitySteps 重新评估后的带方向持仓步数，将据此重置计划平仓量
     */
    public CoreLiquidationState refreshed(CoreMarginMode nextMarginMode, long nextPriceSequence,
                                          long nextSignedQuantitySteps) {
        if (status != Status.PLANNED) throw new IllegalStateException("only planned liquidation can refresh");
        return new CoreLiquidationState(liquidationId, userId, instrumentId, nextMarginMode, positionSide,
                nextPriceSequence, nextSignedQuantitySteps,
                Math.absExact(nextSignedQuantitySteps), 0, 0, 0, 0, Status.PLANNED);
    }

    /**
     * 仅取消尚未执行的强平计划，清空亏空、执行费用和撤单游标，返回取消记录。
     */
    public CoreLiquidationState canceled() {
        if (status != Status.PLANNED) throw new IllegalStateException("only planned liquidation can cancel");
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                0, 0, 0, 0, Status.CANCELED, 0);
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

    /**
     * 保存强平执行价格和费用；仍有亏空则转待保险基金，否则转完成，并清掉撤单游标。
     * @param uncoveredUnits 执行后仍未覆盖的亏空，0 表示可直接结束本次强平
     * @param priceTicks 本次强平执行价格，单位为最小价格 tick
     * @param feeRatePpm 本次强平执行采用的费用比例，单位为百万分之一
     * @param feeUnits 本次强平记录的非负费用金额，单位为结算资产最小单位
     */
    public CoreLiquidationState executed(long uncoveredUnits, long priceTicks, long feeRatePpm, long feeUnits) {
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps, uncoveredUnits,
                priceTicks, feeRatePpm, feeUnits,
                uncoveredUnits > 0 ? Status.INSURANCE_REQUIRED : Status.COMPLETED, 0);
    }

    /**
     * 从剩余亏空中扣掉本次实际覆盖金额，再使用调用方指定的下一阶段；覆盖量必须为正且不能超出亏空。
     * @param coveredUnits 保险基金或 ADL 本次实际覆盖的亏空金额，必须为正且不能超过剩余亏空
     * @param nextStatus 调用方决定的下一状态，这里只构造并校验新记录
     */
    public CoreLiquidationState covered(long coveredUnits, Status nextStatus) {
        if (coveredUnits <= 0 || coveredUnits > deficitUnits) {
            throw new IllegalArgumentException("invalid liquidation coverage");
        }
        return new CoreLiquidationState(liquidationId, userId, instrumentId, marginMode, positionSide,
                triggerPriceSequence, signedQuantitySteps, closeQuantitySteps,
                Math.subtractExact(deficitUnits, coveredUnits), executionPriceTicks,
                liquidationFeeRatePpm, liquidationFeeUnits, nextStatus, 0);
    }
}
