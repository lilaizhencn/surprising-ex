package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.support.math.CoreContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**  计算永续和交割成交中新增加仓部分需要的保证金；只返回金额，资金冻结和持仓更新由结算流程负责。 构造约定： 无状态计算工具，不需要为每笔成交创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FuturesFillCalculator {

    /**
     * 先按成交后总持仓选档，再取币对基础、风险档位、用户杠杆三者中最高的保证金率，计算本次新开仓部分的要求。
     * @param instrument 该期货合约的类型、精度和风险档位配置
     * @param projectedQuantitySteps 成交后的带方向总持仓步数，选档时使用其绝对值
     * @param openSteps 本次成交中新增加仓的绝对数量步数，0 表示全部是在平仓
     * @param priceTicks 本次成交价格 tick，同时用于选档和保证金换算
     * @param leveragePpm 用户设置的杠杆倍数，按一百万倍保存
     */
    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long openSteps,
                                            long priceTicks,
                                            long leveragePpm) {
        if (openSteps <= 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                leverageRate);
        return com.surprising.instrument.api.math.PerpetualContractMath.initialMarginUnits(
                instrument.contractType(), openSteps, priceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits(), effectiveRate);
    }
}
