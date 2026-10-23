package com.surprising.aeron.support.math;

import com.surprising.instrument.api.model.ContractType;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.math.BigInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import static com.surprising.aeron.support.math.CoreArithmetic.*;

/**  交易 Core 使用的合约金额换算入口，按现货、期货和期权选择各自的数学口径。所有金额都以整数最小单位传递，计算本身不修改余额、订单或持仓。 构造约定：只提供无状态计算，不需要为订单或账户创建计算器实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreContractMath {

    /** 任意精度计算使用的百万分之一分母，避免大额中间乘积改变比例口径。 */
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    /** 杠杆和保证金率都按一百万倍缩放，求倒数时分子因此为一万亿。 */
    private static final long LEVERAGE_RATE_NUMERATOR = 1_000_000_000_000L;

    /**
     * 按订单规模选风险档位，再计算开仓保证金；没有数量或期权买方不收取这项保证金。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param side 本次委托的买卖方向，期权买方和卖方使用不同保证金规则
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    public static long openingMarginUnits(
            CoreInstrument instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long indexPriceTicks,
            long forwardPriceTicks) {
        if (quantitySteps <= 0 || instrument.contractType().isOption() && side == CoreOrderSide.BUY) {
            return 0;
        }
        if (instrument.contractType() == ContractType.SPOT)
            throw new IllegalArgumentException("unsupported contract type: " + instrument.contractType());
        CoreRiskLimitBracket bracket = riskBracket(instrument, riskNotionalUnits(instrument, quantitySteps,
                instrument.contractType().isOption() ? indexPriceTicks : priceTicks));
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps, bracket.initialMarginRatePpm(),
                indexPriceTicks, forwardPriceTicks, bracket.optionMarginFactorPpm());
    }

    /**
     * 把指定杠杆换算成初始保证金率，并向上取整，避免整数精度使要求低于该杠杆对应的比例。
     * @param leveragePpm 杠杆倍数，按一百万倍缩放保存，必须为正
     */
    public static long initialMarginRateFromLeverage(long leveragePpm) {
        if (leveragePpm <= 0) {
            throw new IllegalArgumentException("leverage must be positive");
        }
        return divideCeiling(LEVERAGE_RATE_NUMERATOR, leveragePpm);
    }

    /**
     * 使用调用方给出的保证金率计算开仓要求，期权风险调整系数采用一百万，即一倍。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param side 本次委托的买卖方向，期权买方和卖方使用不同保证金规则
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param initialMarginRatePpm 需要采用的初始保证金率，单位为百万分之一
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    public static long openingMarginUnits(
            CoreInstrument instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long initialMarginRatePpm,
            long indexPriceTicks,
            long forwardPriceTicks) {
        return openingMarginUnits(instrument, side, priceTicks, quantitySteps, initialMarginRatePpm,
                indexPriceTicks, forwardPriceTicks, 1_000_000L);
    }

    /**
     * 按合约类型调用期权或期货公式；数量不为正时不产生开仓保证金，不在这里实际冻结资金。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param side 本次委托的买卖方向，期权买方和卖方使用不同保证金规则
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param initialMarginRatePpm 需要采用的初始保证金率，单位为百万分之一
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     * @param optionMarginFactorPpm 期权风险金额的调整系数，按一百万倍缩放保存
     */
    public static long openingMarginUnits(
            CoreInstrument instrument,
            CoreOrderSide side,
            long priceTicks,
            long quantitySteps,
            long initialMarginRatePpm,
            long indexPriceTicks,
            long forwardPriceTicks,
            long optionMarginFactorPpm) {
        if (quantitySteps <= 0) {
            return 0;
        }
        if (instrument.contractType().isOption()) {
            return OptionContractMath.openingMarginUnits(instrument, side, priceTicks, quantitySteps,
                initialMarginRatePpm, indexPriceTicks, forwardPriceTicks, optionMarginFactorPpm);
        }
        return PerpetualContractMath.initialMarginUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits(),
                initialMarginRatePpm);
    }

    /**
     * 按当前持仓规模选择维持保证金档位，再用各产品自己的价格与精度计算持仓维持要求。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数，正数为多头、负数为空头
     * @param markPriceTicks 风险估值或资金费使用的标记价格，单位为价格 tick
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    public static long maintenanceMarginUnits(
            CoreInstrument instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long indexPriceTicks,
            long forwardPriceTicks) {
        if (instrument.contractType() == ContractType.SPOT)
            throw new IllegalArgumentException("unsupported contract type: " + instrument.contractType());
        CoreRiskLimitBracket bracket = maintenanceRiskBracket(instrument, riskNotionalUnits(instrument,
                Math.absExact(signedQuantitySteps), instrument.contractType().isOption()
                        ? indexPriceTicks : markPriceTicks));
        long maintenanceMarginRatePpm = bracket.maintenanceMarginRatePpm();
        if (instrument.contractType().isOption()) {
            return OptionContractMath.maintenanceMarginUnits(instrument, signedQuantitySteps, markPriceTicks,
                indexPriceTicks, forwardPriceTicks, bracket, maintenanceMarginRatePpm);
        }
        return PerpetualContractMath.maintenanceMarginUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits(), maintenanceMarginRatePpm);
    }

    /**
     * 为新风险选择档位，并拒绝超过所选档位金额上限的规模，供需要严格准入的路径使用。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param notionalUnits 用于选风险档位的非负名义金额，单位与该产品风险档位配置一致
     */
    public static CoreRiskLimitBracket riskBracket(CoreInstrument instrument, long notionalUnits) {
        // 已按下界定位的候选档位，还要核对当前规模是否超过它的上限。
        CoreRiskLimitBracket bracket = bracketForNotional(instrument, notionalUnits);
        if (notionalUnits > bracket.notionalCapUnits()) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "position notional exceeds instrument risk brackets");
        }
        return bracket;
    }

    /**
     * 按名义金额定位档位，不执行新开仓的上限拒绝，让已有持仓仍可获得维持风险参数。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param notionalUnits 用于选风险档位的非负名义金额，单位与该产品风险档位配置一致
     */
    public static CoreRiskLimitBracket maintenanceRiskBracket(CoreInstrument instrument, long notionalUnits) {
        return bracketForNotional(instrument, notionalUnits);
    }

    /**
     * 选出金额下界不高于当前规模、且下界最高的档位，不依赖配置列表恰好按大小排序；没有适用档位时拒绝。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param notionalUnits 用于选风险档位的非负名义金额，单位与该产品风险档位配置一致
     */
    private static CoreRiskLimitBracket bracketForNotional(CoreInstrument instrument, long notionalUnits) {
        if (notionalUnits < 0) {
            throw new IllegalArgumentException("notional must not be negative");
        }
        // 遍历到目前为止最合适的档位，尚未找到时为 null。
        CoreRiskLimitBracket selected = null;
        for (CoreRiskLimitBracket /* 本轮检查的档位，只有金额下界适用且比原候选更高才会选中。 */ candidate : instrument.riskLimitBrackets()) {
            if (candidate.notionalFloorUnits() <= notionalUnits
                    && (selected == null
                    || candidate.notionalFloorUnits() > selected.notionalFloorUnits())) {
                selected = candidate;
            }
        }
        if (selected == null) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "position notional has no risk bracket");
        }
        return selected;
    }

    /**
     * 按线性或反向合约口径计算从入场价到退出价的持仓盈亏，结果使用结算资产最小整数单位。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数，正数为多头、负数为空头
     * @param entryPriceTicks 持仓入场价格，单位为价格 tick
     * @param exitPriceTicks 平仓或估值时采用的退出价格，单位为价格 tick
     */
    public static long pnlUnits(
            CoreInstrument instrument,
            long signedQuantitySteps,
            long entryPriceTicks,
            long exitPriceTicks) {
        return PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), signedQuantitySteps,
                entryPriceTicks, exitPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    /**
     * 按现货成交金额、期权权利金或期货名义金额计算费用；绝对费用向上取整，收费返回负数，返佣返回正数。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param feeRatePpm 有效手续费率，单位为百万分之一，正数收费、负数返佣
     */
    public static long feeDeltaUnits(
            CoreInstrument instrument,
            long priceTicks,
            long quantitySteps,
            long feeRatePpm) {
        if (feeRatePpm == 0) {
            return 0;
        }
        // 按当前产品口径换算的收费基数，不把现货、期权和反向合约金额混算。
        long notional = instrument.contractType() == ContractType.SPOT
                ? spotQuoteUnits(instrument, priceTicks, quantitySteps)
                : instrument.contractType().isOption()
                ? OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps)
                : PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        // 尚未附上收费或返佣符号的费用绝对金额，long 路径和大整数路径写入同一结果。
        long fee;
        // 取绝对值后的有效费率，保证收费和返佣先按同样精度计算金额。
        long absoluteRate = Math.absExact(feeRatePpm);
        try {
            fee = divideCeiling(Math.multiplyExact(notional, absoluteRate), 1_000_000L);
        } catch (/* 标记 long 中间乘法无法容纳结果；改用大整数保留原费用和舍入口径。 */ ArithmeticException overflow) {
            fee = divideCeiling(big(notional).multiply(big(absoluteRate)), PPM);
        }
        return feeRatePpm > 0 ? Math.negateExact(fee) : fee;
    }

    /**
     * 把数量和价格换算成当前产品的名义金额；期权使用权利金口径，数量不为正时返回 0。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     */
    public static long notionalUnits(CoreInstrument instrument, long quantitySteps, long priceTicks) {
        if (quantitySteps <= 0) return 0;
        if (instrument.contractType() == ContractType.SPOT) {
            return spotQuoteUnits(instrument, priceTicks, quantitySteps);
        }
        if (instrument.contractType().isOption()) {
            return OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        }
        return PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    /**
     * 把现货数量步数换成基础资产的最小整数金额，溢出时直接报错，不截断金额。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     */
    public static long spotBaseUnits(CoreInstrument instrument, long quantitySteps) {
        return Math.multiplyExact(quantitySteps, instrument.quantityStepUnits());
    }

    /**
     * 按现货价格、数量和币对乘数计算报价资产金额，任一整数乘积溢出都会报错。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     */
    public static long spotQuoteUnits(CoreInstrument instrument, long priceTicks, long quantitySteps) {
        return Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps), instrument.notionalMultiplierUnits());
    }

    /**
     * 换算风险选档使用的名义金额；期权用传入的标的参考价格，其他产品沿用自身名义金额计算。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param referencePriceTicks 本次风险规模换算使用的参考价格，单位为价格 tick
     */
    public static long riskNotionalUnits(CoreInstrument instrument, long quantitySteps, long referencePriceTicks) {
        if (quantitySteps <= 0) return 0;
        return instrument.contractType().isOption()
                ? OptionContractMath.optionPremiumUnits(instrument, referencePriceTicks, quantitySteps)
                : notionalUnits(instrument, quantitySteps, referencePriceTicks);
    }

    /**
     * 按名义金额、费率和持仓方向计算资金费现金变化；正数为入账，负数为扣款，除法按现有整数口径截断。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数，正数为多头、负数为空头
     * @param markPriceTicks 风险估值或资金费使用的标记价格，单位为价格 tick
     * @param fundingRatePpm 本期资金费率，单位为百万分之一，正负方向按持仓多空共同决定
     */
    public static long fundingDeltaUnits(
            CoreInstrument instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long fundingRatePpm) {
        // 按合约类型和标记价算出的资金费名义金额，尚未乘以费率和持仓方向。
        long notional = PerpetualContractMath.notionalUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits());
        try {
            // 资金费除法的中间分子，先乘费率，再带上持仓方向，最后统一转换为账户现金方向。
            long numerator = Math.multiplyExact(notional, fundingRatePpm);
            numerator = Math.multiplyExact(numerator, Long.signum(signedQuantitySteps));
            return Math.negateExact(numerator / 1_000_000L);
        } catch (/* 说明 long 中间乘积或取负溢出，随后尝试用大整数完成同一公式。 */ ArithmeticException overflow) {
            // 大整数路径算出的带方向支付额，取负后才是账户现金变化，最终仍要求能表示为 long。
            BigInteger signedPayment = big(notional).multiply(big(fundingRatePpm))
                    .multiply(BigInteger.valueOf(Long.signum(signedQuantitySteps))).divide(PPM);
            return signedPayment.negate().longValueExact();
        }
    }

    /**
     * 为同方向加仓计算新的入场价格，反向合约使用价格倒数加权，其他合约使用普通数量加权。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param currentAbs 已有持仓的绝对数量，单位为数量步数
     * @param currentPrice 已有持仓的入场价格，单位为价格 tick
     * @param addedAbs 同方向新增持仓的绝对数量，单位为数量步数
     * @param addedPrice 新增持仓的成交价格，单位为价格 tick
     */
    public static long weightedEntryPrice(
            CoreInstrument instrument,
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        return instrument.contractType().isInverse()
                ? weightedInverseEntryPrice(currentAbs, currentPrice, addedAbs, addedPrice)
                : weightedLinearEntryPrice(currentAbs, currentPrice, addedAbs, addedPrice);
    }

    /**
     * 把已有和新增持仓的价格按各自数量加权，再用整数除法得到价格 tick；大额中间值使用任意精度计算。
     * @param currentAbs 已有持仓的绝对数量，单位为数量步数
     * @param currentPrice 已有持仓的入场价格，单位为价格 tick
     * @param addedAbs 同方向新增持仓的绝对数量，单位为数量步数
     * @param addedPrice 新增持仓的成交价格，单位为价格 tick
     */
    private static long weightedLinearEntryPrice(
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        try {
            // 已有与新增两部分数量乘价格后的加权总额，还需要除以两部分的总数量。
            long value = Math.addExact(Math.multiplyExact(currentAbs, currentPrice),
                    Math.multiplyExact(addedAbs, addedPrice));
            return value / Math.addExact(currentAbs, addedAbs);
        } catch (/* long 乘加无法容纳中间值，随后用大整数计算同一加权总额。 */ ArithmeticException overflow) {
            // 已有与新增两部分数量乘价格后的加权总额，还需要除以两部分的总数量。
            BigInteger value = big(currentAbs).multiply(big(currentPrice))
                    .add(big(addedAbs).multiply(big(addedPrice)));
            return value.divide(big(Math.addExact(currentAbs, addedAbs))).longValueExact();
        }
    }

    /**
     * 按反向合约的倒数价格关系合并入场价，沿用共享四舍五入规则，返回值至少保留一个价格 tick。
     * @param currentAbs 已有持仓的绝对数量，单位为数量步数
     * @param currentPrice 已有持仓的入场价格，单位为价格 tick
     * @param addedAbs 同方向新增持仓的绝对数量，单位为数量步数
     * @param addedPrice 新增持仓的成交价格，单位为价格 tick
     */
    private static long weightedInverseEntryPrice(
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        try {
            // 反向加权公式的分子：总数量乘已有价格再乘新增价格，尚未做除法。
            long numerator = Math.multiplyExact(Math.addExact(currentAbs, addedAbs), currentPrice);
            numerator = Math.multiplyExact(numerator, addedPrice);
            // 反向加权公式的分母：已有数量乘新增价格，加上新增数量乘已有价格。
            long denominator = Math.addExact(Math.multiplyExact(currentAbs, addedPrice),
                    Math.multiplyExact(addedAbs, currentPrice));
            return Math.max(1, divideRounded(numerator, denominator));
        } catch (/* long 乘加中间值溢出，改用大整数保留相同反向加权与舍入规则。 */ ArithmeticException overflow) {
            // 反向加权公式的分子：总数量乘已有价格再乘新增价格，尚未做除法。
            BigInteger numerator = big(Math.addExact(currentAbs, addedAbs))
                    .multiply(big(currentPrice)).multiply(big(addedPrice));
            // 反向加权公式的分母：已有数量乘新增价格，加上新增数量乘已有价格。
            BigInteger denominator = big(currentAbs).multiply(big(addedPrice))
                    .add(big(addedAbs).multiply(big(currentPrice)));
            return Math.max(1, divideRounded(numerator, denominator));
        }
    }
}
