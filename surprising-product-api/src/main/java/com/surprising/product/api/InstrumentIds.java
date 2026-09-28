package com.surprising.product.api;

/** Canonical decimal representation of the database-assigned permanent instrument ID. */
public final class InstrumentIds {
    private InstrumentIds() { }

    public static boolean valid(String value) {
        try { parse(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public static int parse(String value) {
        if (value == null || value.isEmpty() || value.length() > 10 || value.charAt(0) == '0') {
            throw new IllegalArgumentException("positive canonical instrumentId is required");
        }
        long result = 0;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("instrumentId must contain decimal digits only");
            }
            result = result * 10 + digit - '0';
        }
        if (result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("instrumentId exceeds supported range");
        }
        return (int) result;
    }
}
