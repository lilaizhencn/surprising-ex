package com.surprising.aeron.service.state;

import java.math.BigInteger;

public final class CoreRoundingPolicy {

    private CoreRoundingPolicy() {
    }

    public static RoundedUnits feeCeiling(long numerator, long denominator) {
        requireDenominator(denominator);
        if (numerator < 0) {
            throw new IllegalArgumentException("fee numerator must not be negative");
        }
        long quotient = numerator / denominator;
        long units = numerator % denominator == 0 ? quotient : Math.addExact(quotient, 1);
        long residual = Math.subtractExact(Math.multiplyExact(units, denominator), numerator);
        return new RoundedUnits(units, residual);
    }

    public static RoundedUnits fundingTruncate(long numerator, long denominator) {
        requireDenominator(denominator);
        long units = numerator / denominator;
        long residual = Math.subtractExact(numerator, Math.multiplyExact(units, denominator));
        return new RoundedUnits(units, residual);
    }

    public static RoundedUnits signedHalfUp(long numerator, long denominator) {
        requireDenominator(denominator);
        BigInteger[] divided = BigInteger.valueOf(numerator)
                .divideAndRemainder(BigInteger.valueOf(denominator));
        BigInteger units = divided[0];
        if (divided[1].abs().shiftLeft(1).compareTo(BigInteger.valueOf(denominator)) >= 0) {
            units = units.add(BigInteger.valueOf(divided[1].signum()));
        }
        long rounded = units.longValueExact();
        long residual = Math.subtractExact(numerator, Math.multiplyExact(rounded, denominator));
        return new RoundedUnits(rounded, residual);
    }

    public static FundsPosting roundingResidualPosting(String asset, RoundedUnits rounded) {
        if (rounded == null || rounded.residual() == 0) {
            throw new IllegalArgumentException("rounding residual is required");
        }
        return new FundsPosting(asset, FundsPosting.OwnerKind.TREASURY, 0,
                FundsPosting.Subledger.ROUNDING_RESIDUAL, rounded.residual());
    }

    private static void requireDenominator(long denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("rounding denominator must be positive");
        }
    }

    public record RoundedUnits(long units, long residual) {
    }
}
