package com.surprising.realtime.api;

/** Lexicographic order preserves all 64-bit log positions; Lua numbers cannot. */
public final class RealtimeVersion {
    private RealtimeVersion() {}

    public static String of(long sequence, int ordinal) {
        if (sequence < 0 || ordinal < 0)
            throw new IllegalArgumentException("negative realtime version");
        return String.format(java.util.Locale.ROOT, "%019d:%010d", sequence, ordinal);
    }

    public static String fence(long sequence) {
        return of(sequence, Integer.MAX_VALUE);
    }
}
