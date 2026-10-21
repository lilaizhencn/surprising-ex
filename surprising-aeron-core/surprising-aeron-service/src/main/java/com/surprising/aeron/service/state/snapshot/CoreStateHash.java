package com.surprising.aeron.service.state.snapshot;

public final class CoreStateHash {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private CoreStateHash() {
    }

    public static long start() {
        return OFFSET_BASIS;
    }

    public static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= PRIME;
        }
        return result;
    }

    public static long mix(long hash, boolean value) {
        return mix(hash, value ? 1 : 0);
    }

    public static long mix(long hash, String value) {
        long result = mix(hash, value.length());
        int length = value.length();
        int index = 0;
        for (; index < length; index++) {
            char character = value.charAt(index);
            if (character > 0x7f) {
                break;
            }
            result ^= character;
            result *= PRIME;
        }
        if (index == length) return result;
        result = mix(hash, length);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c <= 0x7f) {
                result ^= c;
                result *= PRIME;
            } else if (c <= 0x7ff) {
                result ^= (0xc0 | (c >> 6));
                result *= PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= PRIME;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                int codePoint = Character.toCodePoint(c, value.charAt(++i));
                result ^= (0xf0 | (codePoint >> 18));
                result *= PRIME;
                result ^= (0x80 | ((codePoint >> 12) & 0x3f));
                result *= PRIME;
                result ^= (0x80 | ((codePoint >> 6) & 0x3f));
                result *= PRIME;
                result ^= (0x80 | (codePoint & 0x3f));
                result *= PRIME;
            } else if (Character.isSurrogate(c)) {
                result ^= 0xef;
                result *= PRIME;
                result ^= 0xbf;
                result *= PRIME;
                result ^= 0xbd;
                result *= PRIME;
            } else {
                result ^= (0xe0 | (c >> 12));
                result *= PRIME;
                result ^= (0x80 | ((c >> 6) & 0x3f));
                result *= PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= PRIME;
            }
        }
        return result;
    }
}
