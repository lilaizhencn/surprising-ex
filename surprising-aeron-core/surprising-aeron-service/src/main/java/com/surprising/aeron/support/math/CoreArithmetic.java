package com.surprising.aeron.support.math;

import java.math.BigInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 金额和比例计算共用的整数工具。long 中间值溢出时尝试大整数，但最终返回 long 时仍会检查范围。构造约定：只提供整数计算，不能创建实例来保存任何业务资金状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreArithmetic {
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    /** 先按给定分子分母缩放并截断，再先应用下限、后应用上限。这里的 floor 是保底金额，不是另一个舍入函数。 */
    public static long scaledFloorCapped(
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

    /** 把整数金额乘以百万分之一比例，并按非负金额口径向上取整，避免少算需要覆盖的金额。 */
    public static long scalePpmCeiling(long value, long ratePpm) {
        try {
            return divideCeiling(Math.multiplyExact(value, ratePpm), 1_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(ratePpm)), PPM);
        }
    }

    /** 将数值与基准值的比值放大一百万倍后做整数除法，用于把非负价格差等量换成 ppm 比例。 */
    public static long scalePpmFloor(long value, long divisorValue) {
        try {
            return Math.multiplyExact(value, 1_000_000L) / divisorValue;
        } catch (ArithmeticException overflow) {
            return big(value).multiply(PPM).divide(big(divisorValue)).longValueExact();
        }
    }

    /** 将金额同时乘以两个 ppm 比例，再除以一万亿并向上取整，避免连续舍入提前损失精度。 */
    public static long scalePpmSquaredCeiling(long value, long firstRatePpm, long secondRatePpm) {
        try {
            long numerator = Math.multiplyExact(Math.multiplyExact(value, firstRatePpm), secondRatePpm);
            return divideCeiling(numerator, 1_000_000_000_000L);
        } catch (ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(firstRatePpm)).multiply(big(secondRatePpm)),
                    PPM.multiply(PPM));
        }
    }

    /** 对非负大整数做向上取整除法，有余数就比商多取一。 */
    public static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        return (values[1].signum() == 0 ? values[0] : values[0].add(BigInteger.ONE)).longValueExact();
    }

    /** 对非负 long 做向上取整除法，有余数才给商加一。 */
    public static long divideCeiling(long numerator, long denominator) {
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
    }

    /** 对非负大整数做四舍五入，余数达到除数一半时进一，最后检查结果能否保存为 long。 */
    public static long divideRounded(BigInteger numerator, BigInteger denominator) {
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        BigInteger result = values[1].shiftLeft(1).compareTo(denominator) >= 0
                ? values[0].add(BigInteger.ONE) : values[0];
        return result.longValueExact();
    }

    /** 对非负 long 做四舍五入，显式区分奇偶除数，避免先把二分之一截断而错误进位。 */
    public static long divideRounded(long numerator, long denominator) {
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;
        boolean roundsUp = remainder > denominator / 2
                || (denominator % 2 == 0 && remainder == denominator / 2);
        return roundsUp ? Math.addExact(quotient, 1) : quotient;
    }

    /** 将一个 long 原样转为任意精度整数，用于后续可能超过 long 中间范围的计算。 */
    public static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
