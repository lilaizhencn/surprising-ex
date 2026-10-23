package com.surprising.aeron.service.product.option;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**  期权成交的卖方保证金计算及风险价格检查，并计算卖出权利金可用于覆盖新增保证金的部分；这里只算金额，不直接写账户。 构造约定： 无状态的期权成交计算工具，不需要创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionFillCalculator {

    /**
     * 平仓或买入不增加这项卖方保证金；卖出新开仓按成交后总持仓选档，再以标记权利金和档位参数计算保证金。
     * 调用方应先执行 requireRiskMark，确保指数价及同到期远期价齐备。
     * @param instrument 当前期权的乘数、行权信息和风险档位配置
     * @param projectedQuantitySteps 成交后的带方向持仓步数，选档使用绝对数量及指数价格
     * @param signedFillSteps 本次成交的带方向数量，正数买入、负数卖出
     * @param openSteps 本次成交中新开仓的绝对数量步数，0 表示没有新增开仓
     * @param riskMark 已校验的风险行情，卖出新开仓路径要求非空且具备指数价和远期价
     */
    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long signedFillSteps,
                                            long openSteps,
                                            MarkPriceRuntime riskMark) {
        if (openSteps == 0 || signedFillSteps > 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), riskMark.indexPriceTicks());
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        return CoreContractMath.openingMarginUnits(instrument, CoreOrderSide.SELL,
                riskMark.markPriceTicks(), openSteps, bracket.initialMarginRatePpm(),
                riskMark.indexPriceTicks(), riskMark.forwardPriceTicks(), bracket.optionMarginFactorPpm());
    }

    /**
     * 要求期权成交前已有风险行情，且指数价、同到期远期价都为正，缺失时返回期权风险价格不足的业务拒绝。
     * @param riskMark 本次成交准备采用的风险行情，可以传空以得到统一的业务拒绝
     */
    public static void requireRiskMark(MarkPriceRuntime riskMark) {
        if (riskMark == null || riskMark.indexPriceTicks() <= 0 || riskMark.forwardPriceTicks() <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option fill requires index and same-expiry forward prices");
        }
    }

    /**
     * 算出卖出开仓的权利金能覆盖多少新增保证金，最多取新增保证金与开仓部分权利金中的较小值；无权利金收入或无开仓量时为 0。
     * @param instrument 当前期权的数量与权利金换算配置
     * @param premiumDelta 本次成交的权利金现金变化，正数表示收到权利金
     * @param openSteps 本次新开仓的绝对数量步数
     * @param marginIncrease 本次需要增加的保证金金额，单位为结算资产最小单位
     * @param fillPriceTicks 本次实际成交的权利金价格 tick
     */
    public static long premiumMarginFunding(CoreInstrument instrument, long premiumDelta,
                                            long openSteps, long marginIncrease, long fillPriceTicks) {
        return premiumDelta > 0 && openSteps > 0
                ? Math.min(marginIncrease,
                        OptionContractMath.optionPremiumUnits(instrument, fillPriceTicks, openSteps)) : 0;
    }
}
