package com.surprising.trading.order.service;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import java.math.BigInteger;

/**
 * Initial-margin formulas for order admission.
 * All public inputs and outputs are exchange-core compatible long units; BigInteger is used only
 * inside multiplication/division steps so oversized configurations fail instead of overflowing.
 */
public final class OrderMarginMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final BigInteger PPM_SQUARED = PPM.multiply(PPM);
    private static final long MAX_MARKET_SLIPPAGE_PPM = 999_999L;

    private OrderMarginMath() {
    }

    public static long initialMarginUnits(ContractType contractType,
                                          OrderSide side,
                                          OrderType orderType,
                                          long priceTicks,
                                          long quantitySteps,
                                          Long markPriceTicks,
                                          long marketMaxSlippagePpm,
                                          long notionalMultiplierUnits,
                                          long priceTickUnits,
                                          long settleScaleUnits,
                                          long initialMarginRatePpm) {
        requirePositive(quantitySteps, "quantitySteps");
        requirePositive(notionalMultiplierUnits, "notionalMultiplierUnits");
        requirePositive(priceTickUnits, "priceTickUnits");
        requirePositive(settleScaleUnits, "settleScaleUnits");
        requirePositive(initialMarginRatePpm, "initialMarginRatePpm");
        long effectivePriceTicks = collateralPriceTicks(side, orderType, priceTicks, markPriceTicks,
                marketMaxSlippagePpm, contractType);
        BigInteger margin = contractType == ContractType.INVERSE_PERPETUAL
                ? inverseInitialMargin(quantitySteps, notionalMultiplierUnits, settleScaleUnits,
                effectivePriceTicks, priceTickUnits, initialMarginRatePpm)
                : linearInitialMargin(quantitySteps, effectivePriceTicks, notionalMultiplierUnits,
                initialMarginRatePpm);
        return margin.longValueExact();
    }

    public static long notionalUnits(ContractType contractType,
                                     long quantitySteps,
                                     long effectivePriceTicks,
                                     long notionalMultiplierUnits,
                                     long priceTickUnits,
                                     long settleScaleUnits) {
        requirePositive(quantitySteps, "quantitySteps");
        requirePositive(effectivePriceTicks, "effectivePriceTicks");
        requirePositive(notionalMultiplierUnits, "notionalMultiplierUnits");
        requirePositive(priceTickUnits, "priceTickUnits");
        requirePositive(settleScaleUnits, "settleScaleUnits");
        if (contractType == ContractType.INVERSE_PERPETUAL) {
            BigInteger numerator = big(quantitySteps)
                    .multiply(big(notionalMultiplierUnits))
                    .multiply(big(settleScaleUnits));
            BigInteger denominator = big(effectivePriceTicks).multiply(big(priceTickUnits));
            return divideCeilingToLong(numerator, denominator);
        }
        return big(quantitySteps)
                .multiply(big(effectivePriceTicks))
                .multiply(big(notionalMultiplierUnits))
                .longValueExact();
    }

    public static long initialMarginRateFromLeveragePpm(long leveragePpm) {
        requirePositive(leveragePpm, "leveragePpm");
        if (leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        return divideCeilingToLong(PPM_SQUARED, big(leveragePpm));
    }

    public static long collateralPriceTicks(OrderSide side,
                                            OrderType orderType,
                                            long priceTicks,
                                            Long markPriceTicks,
                                            long marketMaxSlippagePpm,
                                            ContractType contractType) {
        if (orderType == OrderType.MARKET) {
            if (contractType == ContractType.INVERSE_PERPETUAL) {
                return lowerBoundPriceTicks(orderType, priceTicks, markPriceTicks, marketMaxSlippagePpm);
            }
            return upperBoundPriceTicks(orderType, priceTicks, markPriceTicks, marketMaxSlippagePpm);
        }
        requirePositive(priceTicks, "priceTicks");
        if (markPriceTicks == null || markPriceTicks <= 0) {
            return priceTicks;
        }
        if (contractType == ContractType.INVERSE_PERPETUAL && side == OrderSide.BUY) {
            return Math.min(priceTicks, lowerBoundPriceTicks(OrderType.MARKET, 0L, markPriceTicks,
                    marketMaxSlippagePpm));
        }
        if (contractType != ContractType.INVERSE_PERPETUAL && side == OrderSide.SELL) {
            return Math.max(priceTicks, upperBoundPriceTicks(OrderType.MARKET, 0L, markPriceTicks,
                    marketMaxSlippagePpm));
        }
        return priceTicks;
    }

    static long lowerBoundPriceTicks(OrderType orderType,
                                     long priceTicks,
                                     Long markPriceTicks,
                                     long marketMaxSlippagePpm) {
        if (orderType != OrderType.MARKET) {
            requirePositive(priceTicks, "priceTicks");
            return priceTicks;
        }
        requireFreshMarkTicks(markPriceTicks);
        long slippagePpm = boundedSlippagePpm(marketMaxSlippagePpm);
        return Math.max(1L, divideFloor(
                big(markPriceTicks).multiply(big(1_000_000L - slippagePpm)), PPM));
    }

    static long upperBoundPriceTicks(OrderType orderType,
                                     long priceTicks,
                                     Long markPriceTicks,
                                     long marketMaxSlippagePpm) {
        if (orderType != OrderType.MARKET) {
            requirePositive(priceTicks, "priceTicks");
            return priceTicks;
        }
        requireFreshMarkTicks(markPriceTicks);
        long slippagePpm = boundedSlippagePpm(marketMaxSlippagePpm);
        return divideCeilingToLong(big(markPriceTicks).multiply(big(1_000_000L + slippagePpm)), PPM);
    }

    private static BigInteger linearInitialMargin(long quantitySteps,
                                                  long effectivePriceTicks,
                                                  long notionalMultiplierUnits,
                                                  long initialMarginRatePpm) {
        BigInteger numerator = big(quantitySteps)
                .multiply(big(effectivePriceTicks))
                .multiply(big(notionalMultiplierUnits))
                .multiply(big(initialMarginRatePpm));
        return divideCeilingBig(numerator, PPM);
    }

    private static BigInteger inverseInitialMargin(long quantitySteps,
                                                   long notionalMultiplierUnits,
                                                   long settleScaleUnits,
                                                   long effectivePriceTicks,
                                                   long priceTickUnits,
                                                   long initialMarginRatePpm) {
        BigInteger numerator = big(quantitySteps)
                .multiply(big(notionalMultiplierUnits))
                .multiply(big(settleScaleUnits))
                .multiply(big(initialMarginRatePpm));
        BigInteger denominator = big(effectivePriceTicks)
                .multiply(big(priceTickUnits))
                .multiply(PPM);
        return divideCeilingBig(numerator, denominator);
    }

    private static long divideCeilingToLong(BigInteger numerator, BigInteger denominator) {
        return divideCeilingBig(numerator, denominator).longValueExact();
    }

    private static BigInteger divideCeilingBig(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0 || numerator.signum() < 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
        return quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE);
    }

    private static long divideFloor(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0 || numerator.signum() < 0) {
            throw new IllegalArgumentException("positive numerator and denominator are required");
        }
        return numerator.divide(denominator).longValueExact();
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static long boundedSlippagePpm(long marketMaxSlippagePpm) {
        return Math.max(0L, Math.min(MAX_MARKET_SLIPPAGE_PPM, marketMaxSlippagePpm));
    }

    private static void requireFreshMarkTicks(Long markPriceTicks) {
        if (markPriceTicks == null || markPriceTicks <= 0) {
            throw new IllegalArgumentException("fresh mark price ticks are required for market orders");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
