package com.surprising.aeron.service.snapshot.state;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
/** 确定性业务哈希的基础混入函数。按固定字节顺序把数值、布尔或字符串加入六十四位 FNV 风格哈希，长整型乘法允许自然溢出；这里不校验资金守恒，也不提供加密签名。 构造约定：工具类不创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CoreStateHash {

    /** 每轮哈希固定的六十四位起始值。 */
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    /** 每混入一个字节所乘的固定 FNV 质数。 */
    private static final long PRIME = 0x100000001b3L;

    /**
     * 返回固定哈希初值，供每个独立状态或条目开始计算。
     */
    public static long start() {
        return OFFSET_BASIS;
    }

    /**
     * 从低字节到高字节依次混入 long 的八个字节，每个字节先异或再乘固定质数，保持固定六十四位回绕结果。
     * @param hash 混入当前值之前的哈希
     * @param value 按低字节优先加入的六十四位整数
     */
    public static long mix(long hash, long value) {
        // 当前累积哈希；非 ASCII 分支会用原输入 hash 重新初始化。
        long result = hash;
        for (/* 当前 long 字节的右移位数，依次为零、八直到五十六。 */ int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= PRIME;
        }
        return result;
    }

    /**
     * 把 true 和 false 转成数值一和零，再沿用八字节整数混入规则。
     * @param hash 混入当前值之前的哈希
     * @param value 要转换为零或一的布尔值
     */
    public static long mix(long hash, boolean value) {
        return mix(hash, value ? 1 : 0);
    }

    /**
     * 先混入字符串的 UTF-16 单元长度，再加入 UTF-8 字节。全 ASCII 时直接处理字符；遇到非 ASCII 则从原哈希重新开始，正确组合代理对，孤立代理单元按替换字符编码。这里没有为空值提供默认字符串。
     * @param hash 混入当前值之前的哈希
     * @param value 要加入长度及 UTF-8 内容的非空字符串
     */
    public static long mix(long hash, String value) {
        // 当前累积哈希；非 ASCII 分支会用原输入 hash 重新初始化。
        long result = mix(hash, value.length());
        // 字符串的 UTF-16 单元数，并非 UTF-8 字节数。
        int length = value.length();
        // ASCII 快速分支已经检查到的字符下标。
        int index = 0;
        for (; index < length; index++) {
            // 快速分支当前 UTF-16 字符，超过 ASCII 后转入完整编码。
            char character = value.charAt(index);
            if (character > 0x7f) {
                break;
            }
            result ^= character;
            result *= PRIME;
        }
        if (index == length) return result;
        result = mix(hash, length);
        for (/* 完整 UTF-8 编码分支的 UTF-16 下标；合法代理对会额外前进一项。 */ int i = 0; i < length; i++) {
            // 完整编码分支当前 UTF-16 单元，用于判断字节数及代理对。
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
                // 由合法高低代理对合成的完整 Unicode 码点，需要编码为四个 UTF-8 字节。
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
