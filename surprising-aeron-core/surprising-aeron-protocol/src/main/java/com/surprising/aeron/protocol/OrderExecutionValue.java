package com.surprising.aeron.protocol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Exact unsigned 128-bit sum(price ticks × filled steps), stored as primitives on the Lane. */
public final class OrderExecutionValue {
    private OrderExecutionValue() { }
    public static long addHigh(long high, long low, long price, long quantity) {
        if (price <= 0 || quantity <= 0) throw new IllegalArgumentException("positive fill required");
        if (high < 0) return -1; // The source order explicitly has unrecorded execution history.
        long product = price * quantity;
        long nextLow = low + product;
        return Math.addExact(Math.addExact(high, Math.multiplyHigh(price, quantity)),
                Long.compareUnsigned(nextLow, low) < 0 ? 1 : 0);
    }
    public static long addLow(long low, long price, long quantity) { return low + price * quantity; }
    public static BigInteger value(long high, long low) {
        if (high < 0) return null;
        return BigInteger.valueOf(high).shiftLeft(64).add(new BigInteger(Long.toUnsignedString(low)));
    }
    public static String average(long high, long low, long filled) {
        BigInteger value = value(high, low);
        return value == null || filled == 0 ? null : new BigDecimal(value)
                .divide(BigDecimal.valueOf(filled), 18, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
