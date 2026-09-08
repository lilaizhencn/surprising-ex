package com.surprising.aeron.service.state;

/** Stable conservative dependency partitions, independent of machine/thread topology. */
public final class TradingDependencyMask {
    private TradingDependencyMask() { }

    public static long account(long userId) {
        return 1L << partition(userId);
    }

    public static int partition(long identity) {
        long value = identity;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (int) (value ^ (value >>> 31)) & 63;
    }
}
