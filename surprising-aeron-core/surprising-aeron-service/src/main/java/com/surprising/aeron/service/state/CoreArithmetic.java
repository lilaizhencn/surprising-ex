package com.surprising.aeron.service.state;

import java.math.BigInteger;

final class CoreArithmetic {
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private CoreArithmetic() {
    }

    static long scaledFloorCapped(
            long value, long multiplier, long divisor, long floor, long cap) {
        if (value < 0 || multiplier < 0 || divisor <= 0 || floor < 0 || cap < 0) {
            throw new IllegalArgumentException("invalid scaled limit input");
        }
        try {
            long scaled = Math.multiplyExact(value, multiplier) / divisor;
            return Math.min(Math.max(scaled, floor), cap);
        } catch (ArithmeticException overflow) {
            return big(value).multiply(big(multiplier)).divide(big(divisor))
                    .max(big(floor)).min(big(cap)).longValueExact();
        }
    }

    static long scalePpmCeiling(long value, long ratePpm) {
        try {
            return divideCeiling(Math.multiplyExact(value, ratePpm), 1_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(ratePpm)), PPM);
        }
    }

    static long scalePpmFloor(long value, long divisorValue) {
        try {
            return Math.multiplyExact(value, 1_000_000L) / divisorValue;
        } catch (ArithmeticException overflow) {
            return big(value).multiply(PPM).divide(big(divisorValue)).longValueExact();
        }
    }

    static long scalePpmSquaredCeiling(long value, long firstRatePpm, long secondRatePpm) {
        try {
            long numerator = Math.multiplyExact(Math.multiplyExact(value, firstRatePpm), secondRatePpm);
            return divideCeiling(numerator, 1_000_000_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(firstRatePpm)).multiply(big(secondRatePpm)),
                    PPM.multiply(PPM));
        }
    }

    static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        return (values[1].signum() == 0 ? values[0] : values[0].add(BigInteger.ONE)).longValueExact();
    }

    static long divideCeiling(long numerator, long denominator) {
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
    }

    static long divideRounded(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        BigInteger result = values[1].shiftLeft(1).compareTo(denominator) >= 0
                ? values[0].add(BigInteger.ONE) : values[0];
        return result.longValueExact();
    }

    static long divideRounded(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        boolean roundsUp = remainder > denominator / 2
                || (denominator % 2 == 0 && remainder == denominator / 2);
        return roundsUp ? Math.addExact(quotient, 1) : quotient;
    }

    static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
