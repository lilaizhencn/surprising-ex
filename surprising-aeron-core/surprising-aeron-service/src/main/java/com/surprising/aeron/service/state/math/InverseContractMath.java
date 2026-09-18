package com.surprising.aeron.service.state.math;

import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import static com.surprising.aeron.service.state.math.CoreArithmetic.*;
import java.math.BigInteger;

public final class InverseContractMath {
    private InverseContractMath() {}

    public static long weightedEntryPrice(
            CoreInstrumentState instrument,
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
}
