package com.surprising.realtime.api;

/** Lexicographic order preserves all 64-bit log positions; Lua numbers cannot. */
public final class RealtimeVersion {
    private RealtimeVersion() {}

    public static String of(long sequence, int ordinal) {
        return new String(bytes(sequence, ordinal), java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Redis compares fixed-width ASCII versions; encode directly without Formatter/boxing. */
    static byte[] bytes(long sequence, int ordinal) {
        if (sequence < 0 || ordinal < 0)
            throw new IllegalArgumentException("negative realtime version");
        byte[] value = new byte[30];
        java.util.Arrays.fill(value, (byte) '0');
        value[19] = ':';
        putDigits(value, 18, sequence);
        putDigits(value, 29, ordinal);
        return value;
    }

    private static void putDigits(byte[] value, int end, long number) {
        do {
            value[end--] = (byte) ('0' + number % 10);
            number /= 10;
        } while (number != 0);
    }

    public static String fence(long sequence) {
        return of(sequence, Integer.MAX_VALUE);
    }
}
