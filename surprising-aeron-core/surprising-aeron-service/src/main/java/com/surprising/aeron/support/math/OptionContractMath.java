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

/** 期权专用的权利金、虚值程度、卖方保证金和到期现金计算。价格以 tick、数量以数量步数传入，返回金额使用结算资产的最小整数单位，不在这里写入账户。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionContractMath {

    /** 计算卖出开仓订单的冻结要求：从标记价口径保证金中扣除订单权利金覆盖部分，并保留最低风险保证金。 */
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
        long positionMargin = openingMarginUnits(instrument, CoreOrderSide.SELL, markPriceTicks,
                quantitySteps, bracket.initialMarginRatePpm(), indexPriceTicks, forwardPriceTicks,
                bracket.optionMarginFactorPpm());
        long orderPremium = optionPremiumUnits(instrument, orderPriceTicks, quantitySteps);
        long marginLessPremium = Math.max(0, Math.subtractExact(positionMargin, orderPremium));
        long minimumRiskTicks = scalePpmCeiling(indexPriceTicks, instrument.initialMarginRatePpm());
        long minimumOpenMargin = optionPremiumUnits(instrument, minimumRiskTicks, quantitySteps);
        return Math.max(marginLessPremium, minimumOpenMargin);
    }

    /** 按期权价格、数量和合约乘数算出权利金金额，价格和数量必须为正。 */
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
        } catch (ArithmeticException overflow) {
            return big(priceTicks).multiply(big(quantitySteps))
                    .multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
        }
    }

    /** 按标记价给期权持仓估值，多头返回正市值，空头返回负市值。 */
    public static long optionMarketValueUnits(CoreInstrument instrument, long signedQuantitySteps,
                                       long markPriceTicks) {
        long value = optionPremiumUnits(instrument, markPriceTicks, Math.absExact(signedQuantitySteps));
        return signedQuantitySteps > 0 ? value : Math.negateExact(value);
    }

    private static long optionOutOfMoneyTicks(CoreInstrument instrument, long forwardPriceTicks) {
        return instrument.optionType() == OptionType.CALL
                ? Math.max(0, Math.subtractExact(instrument.strikePriceTicks(), forwardPriceTicks))
                : Math.max(0, Math.subtractExact(forwardPriceTicks, instrument.strikePriceTicks()));
    }

    /** 要求指数价和同到期远期价都已提供且为正，否则返回缺失期权风险价格的业务拒绝。 */
    public static void requireOptionRiskPrices(long indexPriceTicks, long forwardPriceTicks) {
        if (indexPriceTicks <= 0 || forwardPriceTicks <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option margin requires index and same-expiry forward prices");
        }
    }

    /** 按看涨或看跌方向计算一份数量步对应的到期内在价值，只计算有效价差，失效时现金额为 0。 */
    public static long optionSettlementCashUnits(
            CoreInstrument instrument,
            long underlyingSettlementPriceTicks) {
        if (!instrument.contractType().isOption() || underlyingSettlementPriceTicks <= 0
                || instrument.optionType() == null || instrument.strikePriceTicks() <= 0) {
            throw new IllegalArgumentException("invalid option settlement input");
        }
        BigInteger settlement = big(underlyingSettlementPriceTicks);
        BigInteger strike = big(instrument.strikePriceTicks());
        BigInteger intrinsic = instrument.optionType() == OptionType.CALL
                ? settlement.subtract(strike).max(BigInteger.ZERO)
                : strike.subtract(settlement).max(BigInteger.ZERO);
        return intrinsic.multiply(big(instrument.notionalMultiplierUnits())).longValueExact();
    }
    /** 期权买方此项保证金为 0。卖方按权利金加风险金额计算，虚值程度可降低档位风险率，但不能低于币对基础要求。 */
    public static long openingMarginUnits(CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                long quantitySteps, long initialMarginRatePpm, long indexPriceTicks, long forwardPriceTicks,
                long optionMarginFactorPpm) {
        if (side == CoreOrderSide.BUY) {
            return 0;
        }
        requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
        long premium = optionPremiumUnits(instrument, priceTicks, quantitySteps);
        long outOfMoneyTicks = optionOutOfMoneyTicks(instrument, forwardPriceTicks);
        long outOfMoneyRatePpm = scalePpmFloor(outOfMoneyTicks, forwardPriceTicks);
        long riskRatePpm = Math.max(instrument.initialMarginRatePpm(),
                Math.max(0, Math.subtractExact(initialMarginRatePpm, outOfMoneyRatePpm)));
        long riskTicks = scalePpmSquaredCeiling(indexPriceTicks, riskRatePpm, optionMarginFactorPpm);
        long risk = optionPremiumUnits(instrument, riskTicks, quantitySteps);
        return Math.addExact(premium, risk);
    }
    /** 期权多头此项维持保证金为 0。 */
    public static long maintenanceMarginUnits(CoreInstrument instrument, long signedQuantitySteps,
                long markPriceTicks, long indexPriceTicks, long forwardPriceTicks, CoreRiskLimitBracket bracket,
                long maintenanceMarginRatePpm) {
        if (signedQuantitySteps > 0) {
            return 0;
        }
        requireOptionRiskPrices(indexPriceTicks, forwardPriceTicks);
        long quantity = Math.absExact(signedQuantitySteps);
        long factor = bracket.optionMarginFactorPpm();
        long underlyingRiskTicks = scalePpmSquaredCeiling(
                indexPriceTicks, maintenanceMarginRatePpm, factor);
        long riskTicks = instrument.optionType() == OptionType.PUT
                ? Math.max(underlyingRiskTicks,
                scalePpmSquaredCeiling(markPriceTicks, maintenanceMarginRatePpm, factor))
                : underlyingRiskTicks;
        return Math.addExact(optionPremiumUnits(instrument, markPriceTicks, quantity),
                optionPremiumUnits(instrument, riskTicks, quantity));
    }
}
