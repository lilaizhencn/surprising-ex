package com.surprising.aeron.service.snapshot.state;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
/** 确定性业务哈希的基础混入函数。按固定字节顺序把数值、布尔或字符串加入六十四位 FNV 风格哈希，长整型乘法允许自然溢出。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreStateHash {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    /** 返回固定哈希初值，供每个独立状态或条目开始计算。 */
    public static long start() {
        return OFFSET_BASIS;
    }

    /** 从低字节到高字节依次混入 long 的八个字节，每个字节先异或再乘固定质数，保持固定六十四位回绕结果。 */
    public static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= PRIME;
        }
        return result;
    }

    /** 把 true 和 false 转成数值一和零，再沿用八字节整数混入规则。 */
    public static long mix(long hash, boolean value) {
        return mix(hash, value ? 1 : 0);
    }

    /** 先混入字符串的 UTF-16 单元长度，再加入 UTF-8 字节。 */
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
