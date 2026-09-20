package com.surprising.aeron.service.state.math;

import com.surprising.aeron.service.state.instrument.CoreInstrument;

import static com.surprising.aeron.service.state.math.CoreArithmetic.*;
import java.math.BigInteger;

public final class LinearContractMath {
    private LinearContractMath() {}

    public static long weightedEntryPrice(
            CoreInstrument instrument,
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
}
