package com.surprising.aeron.service.business.option;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;

/** 期权成交的卖方保证金计算及风险价格检查，并计算卖出权利金可用于覆盖新增保证金的部分；这里只算金额，不直接写账户。 */
public final class OptionFillCalculator {
    /** 无状态的期权成交计算工具，不需要创建实例。 */
    private OptionFillCalculator() {
    }

    /**
     * 平仓或买入不增加这项卖方保证金；卖出新开仓按成交后总持仓选档，再以标记权利金和档位参数计算保证金。
     * 调用方应先执行 requireRiskMark，确保指数价及同到期远期价齐备。
     * @param instrument 当前期权的乘数、行权信息和风险档位配置
     * @param projectedQuantitySteps 成交后的带方向持仓步数，选档使用绝对数量及指数价格
     * @param signedFillSteps 本次成交的带方向数量，正数买入、负数卖出
     * @param openSteps 本次成交中新开仓的绝对数量步数，0 表示没有新增开仓
     * @param priceTicks 共享成交计算签名中的成交价格，本方法的保证金估值使用 riskMark 中的标记价
     * @param leveragePpm 共享签名中的用户杠杆；会校验并换算，但期权最终保证金率使用风险档位参数
     * @param riskMark 已校验的风险行情，卖出新开仓路径要求非空且具备指数价和远期价
     */
    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long signedFillSteps,
                                            long openSteps,
                                            long priceTicks,
                                            long leveragePpm,
                                            MarkPriceRuntime riskMark) {
        if (openSteps == 0 || signedFillSteps > 0) return 0;
        // 标的指数价，按成交后总持仓估算风险规模时使用，不能拿期权权利金替代。
        long indexPriceTicks = riskMark == null ? 0 : riskMark.indexPriceTicks();
        // 以标的指数价换算的持仓风险规模，而非本次权利金成交金额。
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), indexPriceTicks);
        // 成交后总持仓对应的风险档位，包含期权保证金率和调整系数。
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        // 档位要求的初始保证金率，单位为百万分之一。
        long bracketRate = bracket.initialMarginRatePpm();
        // 由用户杠杆换算的比例；保留原有计算和正杠杆校验，后续期权公式不选用这个比例。
        long leverageRate = CoreContractMath.initialMarginRateFromLeverage(leveragePpm);
        // 期权使用所选风险档位的比例，后续期权公式还会结合虚值程度和币对基础要求。
        long effectiveRate = bracketRate;
        // 本次期权保证金使用的标记权利金价格 tick，与实际成交价格分开。
        long marginPriceTicks = riskMark.markPriceTicks();
        return CoreContractMath.openingMarginUnits(instrument,
                signedFillSteps > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                marginPriceTicks, openSteps, effectiveRate,
                indexPriceTicks, riskMark == null ? 0 : riskMark.forwardPriceTicks(),
                bracket.optionMarginFactorPpm());
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
