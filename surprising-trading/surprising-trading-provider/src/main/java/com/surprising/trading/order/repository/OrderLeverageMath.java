package com.surprising.trading.order.repository;

import java.math.BigInteger;

/**
 * 不使用浮点运算转换杠杆 ppm 和保证金率 ppm。
 */
public final class OrderLeverageMath {

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);
    private static final BigInteger PPM_SQUARED = PPM.multiply(PPM);

    private OrderLeverageMath() {
    }

    public static long initialMarginRateFromLeveragePpm(long leveragePpm) {
        if (leveragePpm < 1_000_000L) {
            throw new IllegalArgumentException("leveragePpm must be at least 1x");
        }
        BigInteger[] quotientAndRemainder = PPM_SQUARED.divideAndRemainder(BigInteger.valueOf(leveragePpm));
        BigInteger rounded = quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE);
        return rounded.longValueExact();
    }

    public static long leveragePpmFromInitialMarginRate(long initialMarginRatePpm) {
        if (initialMarginRatePpm <= 0) {
            throw new IllegalArgumentException("initialMarginRatePpm must be positive");
        }
        return PPM_SQUARED.divide(BigInteger.valueOf(initialMarginRatePpm)).longValueExact();
    }
}
