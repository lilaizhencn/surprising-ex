package com.surprising.aeron.service.state;

import java.nio.charset.StandardCharsets;

final class CoreStateHash {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private CoreStateHash() {
    }

    static long start() {
        return OFFSET_BASIS;
    }

    static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= PRIME;
        }
        return result;
    }

    static long mix(long hash, boolean value) {
        return mix(hash, value ? 1 : 0);
    }

    static long mix(long hash, String value) {
        long result = mix(hash, value.length());
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            result ^= Byte.toUnsignedInt(item);
            result *= PRIME;
        }
        return result;
    }
}
