package com.surprising.aeron.service.state.risk;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreLiquidationState;

/**
 * 运行时保存的强平事实，用整数币对编号和配置引用定位目标，记录计划平仓量、执行费用、剩余亏空及撤单继续位置；实际资金变更由强平结算流程执行。
 * @param liquidationId 这次强平流程的唯一编号
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param symbolId 运行时使用的非负币对整数编号；对外币对编号需要通过身份表换回
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param instrument 这笔强平关联的合约配置引用，提供产品类型与金额换算参数
 * @param triggerPriceSequence 触发这次强平判断时采用的行情序号
 * @param signedQuantitySteps 触发强平时的带方向持仓步数，正数多头、负数空头，不能为 0
 * @param closeQuantitySteps 这次计划平掉的绝对数量步数，必须大于 0 且不能超过原持仓绝对量
 * @param deficitUnits 这次强平尚未覆盖的亏空，单位为结算资产最小单位
 * @param executionPriceTicks 记录的强平执行价格 tick，尚未执行时为 0
 * @param liquidationFeeRatePpm 这次强平执行采用的费用比例，单位为百万分之一
 * @param liquidationFeeUnits 这次强平执行记录的费用金额，单位为结算资产最小单位
 * @param status 强平当前阶段，沿用业务模型的计划、执行、保险基金及 ADL 等状态
 * @param nextCancelOrderId 强平撤单阶段下一笔待处理的订单编号，非该阶段必须为 0
 */
public record LiquidationRuntime(long liquidationId, long userId, int symbolId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long triggerPriceSequence, long signedQuantitySteps, long closeQuantitySteps,
                                 long deficitUnits, long executionPriceTicks, long liquidationFeeRatePpm,
                                 long liquidationFeeUnits, CoreLiquidationState.Status status,
                                 long nextCancelOrderId) {
    /**
     * 检查强平身份、配置、持仓方向数量、费用与亏空等基本范围；这里不额外检查不同强平阶段之间的状态转换。
     */
    public LiquidationRuntime {
        if (liquidationId <= 0 || userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                || instrument == null || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0) {
            throw new IllegalArgumentException("invalid runtime liquidation");
        }
    }
}
