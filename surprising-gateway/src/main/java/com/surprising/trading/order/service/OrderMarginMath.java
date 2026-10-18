package com.surprising.trading.order.service;

import com.surprising.trading.api.model.OrderType;
import java.math.BigInteger;

/** 市价订单名义价值校验使用的价格上下界；保证金裁决由 Core 承担。 */
public final class OrderMarginMath {
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final long MAX_MARKET_SLIPPAGE_PPM = 999_999L;

    private OrderMarginMath() { }

    public static long lowerBoundPriceTicks(OrderType orderType,
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

    public static long upperBoundPriceTicks(OrderType orderType,
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
            throw new IllegalArgumentException("fresh mark price ticks are required");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
