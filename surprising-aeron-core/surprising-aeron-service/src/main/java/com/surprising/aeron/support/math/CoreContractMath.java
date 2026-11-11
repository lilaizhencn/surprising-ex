package com.surprising.aeron.support.math;

import com.surprising.aeron.service.order.*;

import com.surprising.instrument.api.model.ContractType;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreRiskLimitBracket;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.math.BigInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import static com.surprising.aeron.support.math.CoreArithmetic.*;

/** 交易 Core 使用的合约金额换算入口，按现货、期货和期权选择各自的数学口径。所有金额都以整数最小单位传递，计算本身不修改余额、订单或持仓。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreContractMath {

    /** 任意精度计算使用的百万分之一分母，避免大额中间乘积改变比例口径。 */
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final long LEVERAGE_RATE_NUMERATOR = 1_000_000_000_000L;

    /** 按订单规模选风险档位，再计算开仓保证金。 */
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
        if (instrument.contractType().isOption())
            return openingMarginUnits(instrument, side, priceTicks, quantitySteps, bracket.initialMarginRatePpm(),
                    indexPriceTicks, forwardPriceTicks, bracket.optionMarginFactorPpm());
        return PerpetualContractMath.initialMarginUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits(),
                bracket.initialMarginRatePpm());
    }

    /** 把指定杠杆换算成初始保证金率，并向上取整，避免整数精度使要求低于该杠杆对应的比例。 */
    public static long initialMarginRateFromLeverage(long leveragePpm) {
        if (leveragePpm <= 0) {
            throw new IllegalArgumentException("leverage must be positive");
        }
        return divideCeiling(LEVERAGE_RATE_NUMERATOR, leveragePpm);
    }

    /** 使用调用方给出的保证金率计算开仓要求，期权风险调整系数采用一百万，即一倍。 */
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

    /** 按合约类型调用期权或期货公式。 */
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

    /** 按当前持仓规模选择维持保证金档位，再用各产品自己的价格与精度计算持仓维持要求。 */
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

    /** 为新风险选择档位，并拒绝超过所选档位金额上限的规模，供需要严格准入的路径使用。 */
    public static CoreRiskLimitBracket riskBracket(CoreInstrument instrument, long notionalUnits) {
        CoreRiskLimitBracket bracket = maintenanceRiskBracket(instrument, notionalUnits);
        if (notionalUnits > bracket.notionalCapUnits()) {
            throw new CoreStateRejectedException("RISK_BRACKET_EXCEEDED",
                    "position notional exceeds instrument risk brackets");
        }
        return bracket;
    }

    /** 按已校验的递增上界选档。 */
    public static CoreRiskLimitBracket maintenanceRiskBracket(CoreInstrument instrument, long notionalUnits) {
        if (notionalUnits < 0) {
            throw new IllegalArgumentException("notional must not be negative");
        }
        var brackets = instrument.riskLimitBrackets();
        for (CoreRiskLimitBracket bracket : brackets) {
            if (notionalUnits < bracket.notionalCapUnits()) return bracket;
        }
        return brackets.getLast();
    }

    /** 按线性或反向合约口径计算从入场价到退出价的持仓盈亏，结果使用结算资产最小整数单位。 */
    public static long pnlUnits(
            CoreInstrument instrument,
            long signedQuantitySteps,
            long entryPriceTicks,
            long exitPriceTicks) {
        return PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), signedQuantitySteps,
                entryPriceTicks, exitPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    /** 按现货成交金额、期权权利金或期货名义金额计算费用。 */
    public static long feeDeltaUnits(
            CoreInstrument instrument,
            long priceTicks,
            long quantitySteps,
            long feeRatePpm) {
        if (feeRatePpm == 0) {
            return 0;
        }
        long notional = instrument.contractType() == ContractType.SPOT
                ? spotQuoteUnits(instrument, priceTicks, quantitySteps)
                : instrument.contractType().isOption()
                ? OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps)
                : PerpetualContractMath.notionalUnits(instrument.contractType(), quantitySteps, priceTicks,
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), instrument.settleScaleUnits());
        long fee;
        long absoluteRate = Math.absExact(feeRatePpm);
        try {
            fee = divideCeiling(Math.multiplyExact(notional, absoluteRate), 1_000_000L);
        } catch (ArithmeticException overflow) {
            fee = divideCeiling(big(notional).multiply(big(absoluteRate)), PPM);
        }
        return feeRatePpm > 0 ? Math.negateExact(fee) : fee;
    }

    /** 把数量和价格换算成当前产品的名义金额。 */
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

    /** 把现货数量步数换成基础资产的最小整数金额，溢出时直接报错，不截断金额。 */
    public static long spotBaseUnits(CoreInstrument instrument, long quantitySteps) {
        return Math.multiplyExact(quantitySteps, instrument.quantityStepUnits());
    }

    /** 按现货价格、数量和币对乘数计算报价资产金额，任一整数乘积溢出都会报错。 */
    public static long spotQuoteUnits(CoreInstrument instrument, long priceTicks, long quantitySteps) {
        return Math.multiplyExact(Math.multiplyExact(priceTicks, quantitySteps), instrument.notionalMultiplierUnits());
    }

    /** 换算风险选档使用的名义金额。 */
    public static long riskNotionalUnits(CoreInstrument instrument, long quantitySteps, long referencePriceTicks) {
        if (quantitySteps <= 0) return 0;
        return instrument.contractType().isOption()
                ? OptionContractMath.optionPremiumUnits(instrument, referencePriceTicks, quantitySteps)
                : notionalUnits(instrument, quantitySteps, referencePriceTicks);
    }

    /** 按名义金额、费率和持仓方向计算资金费现金变化。 */
    public static long fundingDeltaUnits(
            CoreInstrument instrument,
            long signedQuantitySteps,
            long markPriceTicks,
            long fundingRatePpm) {
        long notional = PerpetualContractMath.notionalUnits(instrument.contractType(), signedQuantitySteps,
                markPriceTicks, instrument.notionalMultiplierUnits(), instrument.priceTickUnits(),
                instrument.settleScaleUnits());
        try {
            long numerator = Math.multiplyExact(notional, fundingRatePpm);
            numerator = Math.multiplyExact(numerator, Long.signum(signedQuantitySteps));
            return Math.negateExact(numerator / 1_000_000L);
        } catch (ArithmeticException overflow) {
            BigInteger signedPayment = big(notional).multiply(big(fundingRatePpm))
                    .multiply(BigInteger.valueOf(Long.signum(signedQuantitySteps))).divide(PPM);
            return signedPayment.negate().longValueExact();
        }
    }

    /** 为同方向加仓计算新的入场价格，反向合约使用价格倒数加权，其他合约使用普通数量加权。 */
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

    /** 大额中间值使用任意精度计算。 */
    private static long weightedLinearEntryPrice(
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        try {
            long value = Math.addExact(Math.multiplyExact(currentAbs, currentPrice),
                    Math.multiplyExact(addedAbs, addedPrice));
            return value / Math.addExact(currentAbs, addedAbs);
        } catch (ArithmeticException overflow) {
            BigInteger value = big(currentAbs).multiply(big(currentPrice))
                    .add(big(addedAbs).multiply(big(addedPrice)));
            return value.divide(big(Math.addExact(currentAbs, addedAbs))).longValueExact();
        }
    }

    private static long weightedInverseEntryPrice(
            long currentAbs,
            long currentPrice,
            long addedAbs,
            long addedPrice) {
        try {
            long numerator = Math.multiplyExact(Math.addExact(currentAbs, addedAbs), currentPrice);
            numerator = Math.multiplyExact(numerator, addedPrice);
            long denominator = Math.addExact(Math.multiplyExact(currentAbs, addedPrice),
                    Math.multiplyExact(addedAbs, currentPrice));
            return Math.max(1, divideRounded(numerator, denominator));
        } catch (ArithmeticException overflow) {
            BigInteger numerator = big(Math.addExact(currentAbs, addedAbs))
                    .multiply(big(currentPrice)).multiply(big(addedPrice));
            BigInteger denominator = big(currentAbs).multiply(big(addedPrice))
                    .add(big(addedAbs).multiply(big(currentPrice)));
            return Math.max(1, divideRounded(numerator, denominator));
        }
    }

    /** 按单步费用计算冻结额度，避免先计算整笔费用时的分片舍入造成少冻结。 */
    public static long fragmentationSafeFeeDebit(CoreInstrument instrument,
                                                  OrderCommand.ResolvedPlaceOrder order) {
        long feePerStep = feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }

    /** 先按成交后总持仓选档，再取币对基础、风险档位、用户杠杆三者中最高的保证金率，计算本次新开仓部分的要求。 */
    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long openSteps,
                                            long priceTicks,
                                            long leveragePpm) {
        if (openSteps <= 0) return 0;
        long projectedNotional = riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), priceTicks);
        var bracket = maintenanceRiskBracket(instrument, projectedNotional);
        long leverageRate = initialMarginRateFromLeverage(leveragePpm);
        long effectiveRate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                leverageRate);
        return PerpetualContractMath.initialMarginUnits(
                instrument.contractType(), openSteps, priceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits(), effectiveRate);
    }
}
