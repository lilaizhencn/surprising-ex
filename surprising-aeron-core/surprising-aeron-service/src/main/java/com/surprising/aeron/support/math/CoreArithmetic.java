package com.surprising.aeron.support.math;

import java.math.BigInteger;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**  金额和比例计算共用的整数工具。向上取整和四舍五入函数按非负被除数、正除数的业务场景使用；long 中间值溢出时尝试大整数，但最终返回 long 时仍会检查范围。 构造约定：只提供整数计算，不能创建实例来保存任何业务资金状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreArithmetic {
    /** 比例按百万分之一保存时使用的大整数分母。 */
    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    /**
     * 先按给定分子分母缩放并截断，再先应用下限、后应用上限；这里的 floor 是保底金额，不是另一个舍入函数。
     * @param value 本次要换算的非负整数金额或数量
     * @param multiplier 缩放比例的非负分子
     * @param divisor 缩放比例的正数分母
     * @param floor 缩放后至少保留的非负下限
     * @param cap 最终结果不能超过的非负上限
     */
    public static long scaledFloorCapped(
            long value, long multiplier, long divisor, long floor, long cap) {
        if (value < 0 || multiplier < 0 || divisor <= 0 || floor < 0 || cap < 0) {
            throw new IllegalArgumentException("invalid scaled limit input");
        }
        try {
            // 缩放后尚未套用保底和封顶限制的整数值。
            long scaled = Math.multiplyExact(value, multiplier) / divisor;
            return Math.min(Math.max(scaled, floor), cap);
        } catch (/* 中间乘积超过 long，随后用大整数缩放并在封顶后转换结果。 */ ArithmeticException overflow) {
            return big(value).multiply(big(multiplier)).divide(big(divisor))
                    .max(big(floor)).min(big(cap)).longValueExact();
        }
    }

    /**
     * 把整数金额乘以百万分之一比例，并按非负金额口径向上取整，避免少算需要覆盖的金额。
     * @param value 本次要换算的非负整数金额或数量
     * @param ratePpm 比例系数，单位为百万分之一
     */
    public static long scalePpmCeiling(long value, long ratePpm) {
        try {
            return divideCeiling(Math.multiplyExact(value, ratePpm), 1_000_000L);
        } catch (/* 金额乘比例时 long 溢出，随后用同一分母做大整数计算。 */ ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(ratePpm)), PPM);
        }
    }

    /**
     * 将数值与基准值的比值放大一百万倍后做整数除法，用于把非负价格差等量换成 ppm 比例。
     * @param value 本次要换算的非负整数金额或数量
     * @param divisorValue 用于求比例的正数基准值
     */
    public static long scalePpmFloor(long value, long divisorValue) {
        try {
            return Math.multiplyExact(value, 1_000_000L) / divisorValue;
        } catch (/* 放大一百万倍的中间值超出 long，改用大整数完成相同比例除法。 */ ArithmeticException overflow) {
            return big(value).multiply(PPM).divide(big(divisorValue)).longValueExact();
        }
    }

    /**
     * 将金额同时乘以两个 ppm 比例，再除以一万亿并向上取整，避免连续舍入提前损失精度。
     * @param value 本次要换算的非负整数金额或数量
     * @param firstRatePpm 第一个比例系数，按一百万倍缩放保存
     * @param secondRatePpm 第二个比例系数，按一百万倍缩放保存
     */
    public static long scalePpmSquaredCeiling(long value, long firstRatePpm, long secondRatePpm) {
        try {
            // 金额依次乘两个比例后的分子，还没有除以两个 ppm 分母。
            long numerator = Math.multiplyExact(Math.multiplyExact(value, firstRatePpm), secondRatePpm);
            return divideCeiling(numerator, 1_000_000_000_000L);
        } catch (/* 双比例乘积超出 long，随后用大整数一次性除以一万亿。 */ ArithmeticException overflow) {
            return divideCeiling(big(value).multiply(big(firstRatePpm)).multiply(big(secondRatePpm)),
                    PPM.multiply(PPM));
        }
    }

    /**
     * 对非负大整数做向上取整除法，有余数就比商多取一；结果超出 long 范围时会报错。
     * @param numerator 要做整数除法的非负被除数
     * @param denominator 整数除法使用的正数除数
     */
    public static long divideCeiling(BigInteger numerator, BigInteger denominator) {
        // 大整数除法同时得到的商与余数，下标 0 是商，下标 1 是余数。
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        return (values[1].signum() == 0 ? values[0] : values[0].add(BigInteger.ONE)).longValueExact();
    }

    /**
     * 对非负 long 做向上取整除法，有余数才给商加一；调用方负责传入正除数。
     * @param numerator 要做整数除法的非负被除数
     * @param denominator 整数除法使用的正数除数
     */
    public static long divideCeiling(long numerator, long denominator) {
        // 未应用向上取整规则的整数商。
        long quotient = numerator / denominator;
        return numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
    }

    /**
     * 对非负大整数做四舍五入，余数达到除数一半时进一，最后检查结果能否保存为 long。
     * @param numerator 要做整数除法的非负被除数
     * @param denominator 整数除法使用的正数除数
     */
    public static long divideRounded(BigInteger numerator, BigInteger denominator) {
        // 大整数除法得到的商和余数，用余数的两倍判断是否达到一半。
        BigInteger[] values = numerator.divideAndRemainder(denominator);
        // 应用四舍五入后的大整数商，返回前还要检查 long 范围。
        BigInteger result = values[1].shiftLeft(1).compareTo(denominator) >= 0
                ? values[0].add(BigInteger.ONE) : values[0];
        return result.longValueExact();
    }

    /**
     * 对非负 long 做四舍五入，显式区分奇偶除数，避免先把二分之一截断而错误进位。
     * @param numerator 要做整数除法的非负被除数
     * @param denominator 整数除法使用的正数除数
     */
    public static long divideRounded(long numerator, long denominator) {
        // 尚未按余数决定是否进位的整数商。
        long quotient = numerator / denominator;
        // 除法剩余部分，用于判断结果是否达到半个最小单位。
        long remainder = numerator % denominator;
        // 余数大于一半，或在偶数除数下恰好等于一半时，需要给商加一。
        boolean roundsUp = remainder > denominator / 2
                || (denominator % 2 == 0 && remainder == denominator / 2);
        return roundsUp ? Math.addExact(quotient, 1) : quotient;
    }

    /**
     * 将一个 long 原样转为任意精度整数，用于后续可能超过 long 中间范围的计算。
     * @param value 要原样转换的 long 数值，可以为正、负或 0
     */
    public static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }
}
