package com.surprising.aeron.service.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** 跨线程就绪提示，每一位使用单独间隔的原始数组槽，减少不同 Lane 写相邻位置。读写使用 acquire/release，采样与清除不是原子的整体操作。 */
public final class PaddedReadyMask {
    private static final int STRIDE = 16;
    /** long 数组元素访问器，提示读取使用 acquire、写入使用 release。 */
    private static final VarHandle CELLS = MethodHandles.arrayElementVarHandle(long[].class);
    private final long[] cells;
    private final int bitCount;

    /** 要求位数在 1 至 64 之间，按每位固定间隔分配原始数组。 */
    public PaddedReadyMask(int bitCount) {
        if (bitCount <= 0 || bitCount > Long.SIZE) {
            throw new IllegalArgumentException("ready bit count must be in [1,64]");
        }
        this.bitCount = bitCount;
        cells = new long[Math.multiplyExact(bitCount, STRIDE)];
    }

    private int offset(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= bitCount) {
            throw new IllegalArgumentException("ready bit is outside its topology");
        }
        return bitIndex * STRIDE;
    }

    /** 先以 acquire 读取提示，已非零就返回假，否则以 release 写入一并返回真。它不是原子的读改写，并发发布可能合并或重复唤醒。 */
    public boolean setBit(int bitIndex) {
        int offset = offset(bitIndex);
        if ((long) CELLS.getAcquire(cells, offset) != 0) return false;
        CELLS.setRelease(cells, offset, 1L);
        return true;
    }

    /** 逐位以 acquire 采样就绪提示并合成掩码，不清除提示，也不构成所有业务完成状态的原子快照。 */
    public long get() {
        long ready = 0;
        for (int bit = 0; bit < bitCount; bit++) {
            if ((long) CELLS.getAcquire(cells, bit * STRIDE) != 0) ready |= 1L << bit;
        }
        return ready;
    }

    /** 逐位采样非零提示后清零并返回所见掩码。 */
    public long getAndClear() {
        long ready = 0;
        for (int bit = 0; bit < bitCount; bit++) {
            int offset = bit * STRIDE;
            if ((long) CELLS.getAcquire(cells, offset) != 0) {
                CELLS.setRelease(cells, offset, 0L);
                ready |= 1L << bit;
            }
        }
        return ready;
    }
}
