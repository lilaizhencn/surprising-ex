package com.surprising.aeron.service.business.derivative;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.math.CoreContractMath;

/**  计算永续和交割成交中新增加仓部分需要的保证金；只返回金额，资金冻结和持仓更新由结算流程负责。 构造约定： 无状态计算工具，不需要为每笔成交创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FuturesFillCalculator {

    /**
     * 先按成交后总持仓选档，再取币对基础、风险档位、用户杠杆三者中最高的保证金率，计算本次新开仓部分的要求。
     * @param instrument 该期货合约的类型、精度和风险档位配置
     * @param projectedQuantitySteps 成交后的带方向总持仓步数，选档时使用其绝对值
     * @param signedFillSteps 本次成交的带方向数量步数，正数买入、负数卖出
     * @param openSteps 本次成交中新增加仓的绝对数量步数，0 表示全部是在平仓
     * @param priceTicks 本次成交价格 tick，同时用于选档和保证金换算
     * @param leveragePpm 用户设置的杠杆倍数，按一百万倍保存
     * @param riskMark 当前风险行情，可为空；期货金额公式主要使用本次成交价格
     */
    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long signedFillSteps,
                                            long openSteps,
                                            long priceTicks,
                                            long leveragePpm,
                                            MarkPriceRuntime riskMark) {
        if (openSteps == 0) return 0;
        // 行情提供的指数价；未提供行情时记为 0，期货分支不靠它给成交定价。
        long indexPriceTicks = riskMark == null ? 0 : riskMark.indexPriceTicks();
        // 成交后总持仓在本次成交价下的风险名义金额，用于选择对应档位。
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), priceTicks);
        // 按总持仓规模定位的档位；这里采用成交结算的选档入口，不执行新委托的金额上限拒绝。
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        // 所选风险档位要求的初始保证金率，单位为百万分之一。
        long bracketRate = bracket.initialMarginRatePpm();
        // 用户杠杆换算成的初始保证金率，按向上取整避免少算保证金。
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        // 三种约束中最严格的比例，只用于本次新开仓数量的保证金计算。
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracketRate), leverageRate);
        // 期货成交新增保证金按实际成交价计算，不能改用期权的标记权利金口径。
        long marginPriceTicks = priceTicks;
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                marginPriceTicks, openSteps, effectiveRate,
                indexPriceTicks, riskMark == null ? 0 : riskMark.forwardPriceTicks(),
                bracket.optionMarginFactorPpm());
    }
}
