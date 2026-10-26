package com.surprising.aeron.support.math;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.instrument.api.model.OptionType;
import java.math.BigInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import static com.surprising.aeron.support.math.CoreArithmetic.*;

/**  期权专用的权利金、虚值程度、卖方保证金和到期现金计算。价格以 tick、数量以数量步数传入，返回金额使用结算资产的最小整数单位，不在这里写入账户。 构造约定：只提供期权数学计算，不需要创建独立实例或持有账户状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionContractMath {

    /**
     * 计算卖出开仓订单的冻结要求：从标记价口径保证金中扣除订单权利金覆盖部分，并保留最低风险保证金。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param orderPriceTicks 新卖单的权利金委托价格，单位为价格 tick
     * @param markPriceTicks 风险估值或资金费使用的标记价格，单位为价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     * @param bracket 上层已选定的期权风险档位，包含保证金率和调整系数
     */
    public static long optionSellOpenOrderMarginUnits(
            CoreInstrument instrument,
            long orderPriceTicks,
            long markPriceTicks,
            long quantitySteps,
            long indexPriceTicks,
            long forwardPriceTicks,
            CoreRiskLimitBracket bracket) {
        if (!instrument.contractType().isOption() || bracket == null || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid option sell-open margin input");
        }
        // 按标记价和风险档位计算的卖方持仓保证金，用作订单冻结的起点。
        long positionMargin = openingMarginUnits(instrument, CoreOrderSide.SELL, markPriceTicks,
                quantitySteps, bracket.initialMarginRatePpm(), indexPriceTicks, forwardPriceTicks,
                bracket.optionMarginFactorPpm());
        // 按该订单委托价可对应的权利金，只参与保证金公式，不代表已经收到账户现金。
        long orderPremium = optionPremiumUnits(instrument, orderPriceTicks, quantitySteps);
        // 扣除订单权利金覆盖部分后仍需预留的金额，最低取 0。
        long marginLessPremium = Math.max(0, Math.subtractExact(positionMargin, orderPremium));
        // 指数价格乘币对基础初始保证金率后的价格尺度风险下限。
        long minimumRiskTicks = scalePpmCeiling(indexPriceTicks, instrument.initialMarginRatePpm());
        // 把最低风险价格按订单数量和合约乘数换成实际结算资产金额。
        long minimumOpenMargin = optionPremiumUnits(instrument, minimumRiskTicks, quantitySteps);
        return Math.max(marginLessPremium, minimumOpenMargin);
    }

    /**
     * 按期权价格、数量和合约乘数算出权利金金额，价格和数量必须为正；大额中间乘积使用大整数保持精确。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     */
    public static long optionPremiumUnits(
            CoreInstrument instrument,
            long priceTicks,
            long quantitySteps) {
        if (!instrument.contractType().isOption() || priceTicks <= 0 || quantitySteps <= 0) {
            throw new IllegalArgumentException("invalid option premium input");
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps),
                    instrument.notionalMultiplierUnits());
        } catch (/* long 连乘中间值溢出，随后用大整数计算相同权利金，最终仍检查 long 范围。 */ ArithmeticException overflow) {
            return big(priceTicks).multiply(big(quantitySteps))
                    .multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
        }
    }

    /**
     * 按标记价给期权持仓估值，多头返回正市值，空头返回负市值；数量绝对值交给权利金计算校验。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数，正数为多头、负数为空头
     * @param markPriceTicks 风险估值或资金费使用的标记价格，单位为价格 tick
     */
    public static long optionMarketValueUnits(CoreInstrument instrument, long signedQuantitySteps,
                                       long markPriceTicks) {
        // 按持仓绝对数量计算的正向市值，再根据原持仓方向决定现金估值符号。
        long value = optionPremiumUnits(instrument, markPriceTicks, Math.absExact(signedQuantitySteps));
        return signedQuantitySteps > 0 ? value : Math.negateExact(value);
    }

    /**
     * 计算相对同到期远期价的虚值价格差：看涨取行权价高出的部分，看跌取远期价高出的部分，实值时为 0。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    private static long optionOutOfMoneyTicks(CoreInstrument instrument, long forwardPriceTicks) {
        return instrument.optionType() == OptionType.CALL
                ? Math.max(0, Math.subtractExact(instrument.strikePriceTicks(), forwardPriceTicks))
                : Math.max(0, Math.subtractExact(forwardPriceTicks, instrument.strikePriceTicks()));
    }

    /**
     * 要求指数价和同到期远期价都已提供且为正，否则返回缺失期权风险价格的业务拒绝。
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     */
    public static void requireOptionRiskPrices(long indexPriceTicks, long forwardPriceTicks) {
        if (indexPriceTicks <= 0 || forwardPriceTicks <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option margin requires index and same-expiry forward prices");
        }
    }

    /**
     * 按看涨或看跌方向计算一份数量步对应的到期内在价值，只计算有效价差，失效时现金额为 0。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param underlyingSettlementPriceTicks 到期使用的标的资产结算价格，单位为价格 tick
     */
    public static long optionSettlementCashUnits(
            CoreInstrument instrument,
            long underlyingSettlementPriceTicks) {
        if (!instrument.contractType().isOption() || underlyingSettlementPriceTicks <= 0
                || instrument.optionType() == null || instrument.strikePriceTicks() <= 0) {
            throw new IllegalArgumentException("invalid option settlement input");
        }
        // 用大整数保存的标的结算价格，防止后续价差和乘数运算产生中间溢出。
        BigInteger settlement = big(underlyingSettlementPriceTicks);
        // 用大整数保存的期权行权价格，与标的结算价格使用同一价格尺度。
        BigInteger strike = big(instrument.strikePriceTicks());
        // 按看涨或看跌方向截取的非负内在价差，还需要乘合约金额乘数。
        BigInteger intrinsic = instrument.optionType() == OptionType.CALL
                ? settlement.subtract(strike).max(BigInteger.ZERO)
                : strike.subtract(settlement).max(BigInteger.ZERO);
        return intrinsic.multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
    }
    /**
     * 期权买方此项保证金为 0；卖方按权利金加风险金额计算，虚值程度可降低档位风险率，但不能低于币对基础要求。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param side 本次委托的买卖方向，期权买方和卖方使用不同保证金规则
     * @param priceTicks 本次成交、冻结或估值采用的价格，单位为最小价格 tick
     * @param quantitySteps 参与计算的数量，单位为币对配置的最小数量步数
     * @param initialMarginRatePpm 需要采用的初始保证金率，单位为百万分之一
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     * @param optionMarginFactorPpm 期权风险金额的调整系数，按一百万倍缩放保存
     */
    public static long openingMarginUnits(CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                long quantitySteps, long initialMarginRatePpm, long indexPriceTicks, long forwardPriceTicks,
                long optionMarginFactorPpm) {
        if (side == CoreOrderSide.BUY) {
            return 0;
        }
        requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
        // 按本次价格和数量计算的权利金，是卖方保证金中需要覆盖的第一部分。
        long premium = optionPremiumUnits(instrument, priceTicks, quantitySteps);
        // 相对同到期远期价的虚值价格差，用来抵减风险率的一部分。
        long outOfMoneyTicks = optionOutOfMoneyTicks(instrument, forwardPriceTicks);
        // 虚值价格差相对远期价的 ppm 比例，按整数除法截断。
        long outOfMoneyRatePpm = scalePpmFloor(outOfMoneyTicks, forwardPriceTicks);
        // 抵减虚值比例后实际采用的风险率，不能低于币对基础初始保证金率。
        long riskRatePpm = Math.max(instrument.initialMarginRatePpm(),
                Math.max(0, Math.subtractExact(initialMarginRatePpm, outOfMoneyRatePpm)));
        // 指数价格依次乘风险率和期权调整系数后，向上取整得到的风险价格。
        long riskTicks = scalePpmSquaredCeiling(indexPriceTicks, riskRatePpm, optionMarginFactorPpm);
        // 将风险价格按订单数量换成结算资产金额，之后与权利金相加。
        long risk = optionPremiumUnits(instrument, riskTicks, quantitySteps);
        return Math.addExact(premium, risk);
    }
    /**
     * 期权多头此项维持保证金为 0；空头需要覆盖标记权利金和风险金额，看跌还比较标记价口径的风险下限。
     * @param instrument 当前币对的合约类型、价格数量精度和结算资产配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数，正数为多头、负数为空头
     * @param markPriceTicks 风险估值或资金费使用的标记价格，单位为价格 tick
     * @param indexPriceTicks 标的资产的指数价格，单位为价格 tick
     * @param forwardPriceTicks 与期权相同到期日的远期价格，单位为价格 tick
     * @param bracket 上层已选定的期权风险档位，包含保证金率和调整系数
     * @param maintenanceMarginRatePpm 当前风险档位要求的维持保证金率，单位为百万分之一
     */
    public static long maintenanceMarginUnits(CoreInstrument instrument, long signedQuantitySteps,
                long markPriceTicks, long indexPriceTicks, long forwardPriceTicks, CoreRiskLimitBracket bracket,
                long maintenanceMarginRatePpm) {
        if (signedQuantitySteps > 0) {
            return 0;
        }
        requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
        // 空头持仓的绝对数量，供权利金和风险金额换算使用。
        long quantity = Math.absExact(signedQuantitySteps);
        // 当前风险档位的期权保证金调整系数，按一百万倍缩放保存。
        long factor = bracket.optionMarginFactorPpm();
        // 以指数价格、维持保证金率和调整系数计算的风险价格。
        long underlyingRiskTicks = scalePpmSquaredCeiling(
                indexPriceTicks, maintenanceMarginRatePpm, factor);
        // 最终采用的维持风险价格，看跌会在指数价和标记价口径中取更高值。
        long riskTicks = instrument.optionType() == OptionType.PUT
                ? Math.max(underlyingRiskTicks,
                scalePpmSquaredCeiling(markPriceTicks, maintenanceMarginRatePpm, factor))
                : underlyingRiskTicks;
        return Math.addExact(optionPremiumUnits(instrument, markPriceTicks, quantity),
                optionPremiumUnits(instrument, riskTicks, quantity));
    }
}
