package com.surprising.aeron.service.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * 跨线程就绪提示，每一位使用单独间隔的原始数组槽，减少不同 Lane 写相邻位置。
 * 读写使用 acquire/release，采样与清除不是原子的整体操作；提示可以合并，队列和事件完成标记才是实际完成依据。
 */
public class PaddedReadyMask {
    /** 相邻提示槽间隔十六个 long，使不同位尽量不写同一缓存行。 */
    private static final int STRIDE = 16;
    /** long 数组元素访问器，提示读取使用 acquire、写入使用 release。 */
    private static final VarHandle CELLS = MethodHandles.arrayElementVarHandle(long[].class);
    /** 实际存储就绪提示的原始数组，非零表示该位被发布过。 */
    private final long[] cells;
    /** 本提示数组实际支持的位数，构造时限定在 1 至 64。 */
    private final int bitCount;

    /**
     * 建立最多 64 位的就绪提示数组，每位单独留出缓存行间隔。
     */
    public PaddedReadyMask() {
        this(Long.SIZE);
    }

    /**
     * 要求位数在 1 至 64 之间，按每位固定间隔分配原始数组。
     * @param bitCount 支持的就绪位数，必须在 1 至 64 之间
     */
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

    /**
     * 先以 acquire 读取提示，已非零就返回假，否则以 release 写入一并返回真；它不是原子的读改写，并发发布可能合并或重复唤醒。
     * @param bitIndex 本提示数组内的零起始位下标
     */
    public boolean setBit(int bitIndex) {
        int offset = offset(bitIndex);
        if ((long) CELLS.getAcquire(cells, offset) != 0) return false;
        CELLS.setRelease(cells, offset, 1L);
        return true;
    }

    /**
     * 校验位下标后，以 release 清除该位提示。
     * @param bitIndex 本提示数组内的零起始位下标
     */
    public void clearBit(int bitIndex) {
        CELLS.setRelease(cells, offset(bitIndex), 0L);
    }

    /**
     * 逐个清除给定掩码对应的提示；调用方保证掩码只包含本拓扑内的位，此处不逐位做范围校验。
     * @param bitsToClear 调用方保证属于本拓扑的待清除提示位集合
     */
    public void clearBits(long bitsToClear) {
        while (bitsToClear != 0) {
            int bit = Long.numberOfTrailingZeros(bitsToClear);
            bitsToClear &= bitsToClear - 1;
            CELLS.setRelease(cells, bit * STRIDE, 0L);
        }
    }

    /**
     * 逐位以 acquire 采样就绪提示并合成掩码，不清除提示，也不构成所有业务完成状态的原子快照。
     */
    public long get() {
        long ready = 0;
        for (int bit = 0; bit < bitCount; bit++) {
            if ((long) CELLS.getAcquire(cells, bit * STRIDE) != 0) ready |= 1L << bit;
        }
        return ready;
    }

    /**
     * 逐位采样非零提示后清零并返回所见掩码；这里只收集唤醒线索，真实结果仍以队列或事件完成标记为准。
     */
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
