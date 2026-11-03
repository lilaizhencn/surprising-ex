package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/**
 * 已提交资金命令的 Owner 独占内存身份表。Cluster 日志和 Core 快照是恢复来源，
 * 活动命令不访问本地数据库。完整 UUID 与四个原语 long 指纹列一起保留到运行时退休。
 */
public final class FundsCommandIndex implements AutoCloseable {
    public enum Match { ABSENT, SAME, CONFLICT }
    private static final int INITIAL_CAPACITY = 1024;
    private long[] high = new long[INITIAL_CAPACITY];
    private long[] low = new long[INITIAL_CAPACITY];
    private long[] fingerprint0 = new long[INITIAL_CAPACITY];
    private long[] fingerprint1 = new long[INITIAL_CAPACITY];
    private long[] fingerprint2 = new long[INITIAL_CAPACITY];
    private long[] fingerprint3 = new long[INITIAL_CAPACITY];
    private byte[] occupied = new byte[INITIAL_CAPACITY];
    private int count;
    private Thread owner;
    private boolean closed;

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("funds index belongs to bound owner thread");
    }

    private void assertOwner() {
        if (owner != null && owner != Thread.currentThread())
            throw new IllegalStateException("funds index belongs to bound owner thread");
    }

    public void assertHealthy() {
        if (closed) throw new IllegalStateException("funds command index is closed");
    }

    public CommandFingerprint get(long msb, long lsb) {
        assertHealthy();
        assertOwner();
        int slot = locate(msb, lsb, high, low, occupied);
        if (occupied[slot] == 0) return null;
        byte[] bytes = new byte[CommandFingerprint.LENGTH];
        writeWord(bytes, 0, fingerprint0[slot]);
        writeWord(bytes, 8, fingerprint1[slot]);
        writeWord(bytes, 16, fingerprint2[slot]);
        writeWord(bytes, 24, fingerprint3[slot]);
        return CommandFingerprint.fromBytes(bytes);
    }

    public CommandFingerprint get(UUID id) {
        return id == null ? null : get(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /** 幂等入口直接比较原语指纹列，命中时不重建 CommandFingerprint 对象。 */
    public Match match(long msb, long lsb, CommandFingerprint fingerprint) {
        assertHealthy();
        assertOwner();
        if (fingerprint == null) throw new IllegalArgumentException("funds identity is required");
        int slot = locate(msb, lsb, high, low, occupied);
        if (occupied[slot] == 0) return Match.ABSENT;
        return sameFingerprint(slot, fingerprint) ? Match.SAME : Match.CONFLICT;
    }

    public void put(long msb, long lsb, CommandFingerprint fingerprint) {
        assertHealthy();
        assertOwner();
        if (fingerprint == null) throw new IllegalArgumentException("funds identity is required");
        int slot = locate(msb, lsb, high, low, occupied);
        if (occupied[slot] != 0) {
            if (!sameFingerprint(slot, fingerprint))
                throw new IllegalStateException("funds command fingerprint conflict");
            return;
        }
        if (count >= occupied.length * 2 / 3) {
            reserveNext();
            slot = locate(msb, lsb, high, low, occupied);
        }
        high[slot] = msb;
        low[slot] = lsb;
        fingerprint0[slot] = readWord(fingerprint, 0);
        fingerprint1[slot] = readWord(fingerprint, 8);
        fingerprint2[slot] = readWord(fingerprint, 16);
        fingerprint3[slot] = readWord(fingerprint, 24);
        occupied[slot] = 1;
        count++;
    }

    public void put(UUID id, CommandFingerprint fingerprint) {
        if (id == null) throw new IllegalArgumentException("funds identity is required");
        put(id.getMostSignificantBits(), id.getLeastSignificantBits(), fingerprint);
    }

    /** 在资金业务变更前完成可能失败的扩容；随后本条身份写入不会再分配表容量。 */
    public void reserveNext() {
        assertHealthy();
        assertOwner();
        if (count >= occupied.length * 2 / 3) grow();
    }

    /** 保持旧快照的 UUID 大端字节序和按完整 UUID 升序排列。 */
    public void write(DataOutput output) throws IOException {
        assertHealthy();
        output.writeInt(count);
        int[] sorted = new int[count];
        int next = 0;
        for (int slot = 0; slot < occupied.length; slot++) {
            if (occupied[slot] != 0) sorted[next++] = slot;
        }
        // UUID 低位到高位逐字节稳定排序，快照阶段只分配原语数组。
        if (count > 1) {
            int[] scratch = new int[count];
            int[] offsets = new int[256];
            for (int pass = 0; pass < 16; pass++) {
                java.util.Arrays.fill(offsets, 0);
                for (int slot : sorted) offsets[uuidByte(slot, pass)]++;
                int position = 0;
                for (int digit = 0; digit < offsets.length; digit++) {
                    int size = offsets[digit];
                    offsets[digit] = position;
                    position += size;
                }
                for (int slot : sorted) scratch[offsets[uuidByte(slot, pass)]++] = slot;
                int[] previous = sorted;
                sorted = scratch;
                scratch = previous;
            }
        }
        for (int slot : sorted) {
            output.writeLong(high[slot]);
            output.writeLong(low[slot]);
            output.writeLong(fingerprint0[slot]);
            output.writeLong(fingerprint1[slot]);
            output.writeLong(fingerprint2[slot]);
            output.writeLong(fingerprint3[slot]);
        }
    }

    private int uuidByte(int slot, int pass) {
        long word = pass < 8 ? low[slot] : high[slot];
        return (int) (word >>> ((pass & 7) * 8)) & 0xff;
    }

    private static long readWord(CommandFingerprint fingerprint, int offset) {
        long word = 0;
        for (int i = 0; i < Long.BYTES; i++) word = (word << 8) | (fingerprint.byteAt(offset + i) & 0xffL);
        return word;
    }

    private static void writeWord(byte[] bytes, int offset, long word) {
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            bytes[offset + i] = (byte) word;
            word >>>= 8;
        }
    }

    private boolean sameFingerprint(int slot, CommandFingerprint fingerprint) {
        return fingerprint0[slot] == readWord(fingerprint, 0)
                && fingerprint1[slot] == readWord(fingerprint, 8)
                && fingerprint2[slot] == readWord(fingerprint, 16)
                && fingerprint3[slot] == readWord(fingerprint, 24);
    }

    private static int locate(long msb, long lsb, long[] highs, long[] lows, byte[] entries) {
        int mask = entries.length - 1;
        long mixed = msb * 0x9E3779B97F4A7C15L ^ Long.rotateLeft(lsb, 29);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        int slot = (int) mixed & mask;
        while (entries[slot] != 0 && (highs[slot] != msb || lows[slot] != lsb))
            slot = (slot + 1) & mask;
        return slot;
    }

    private void grow() {
        if (occupied.length >= 1 << 28)
            throw new IllegalStateException("funds command index capacity exhausted");
        int capacity = occupied.length << 1;
        long[] nextHigh = new long[capacity], nextLow = new long[capacity];
        long[] next0 = new long[capacity], next1 = new long[capacity];
        long[] next2 = new long[capacity], next3 = new long[capacity];
        byte[] nextOccupied = new byte[capacity];
        for (int slot = 0; slot < occupied.length; slot++) {
            if (occupied[slot] == 0) continue;
            int target = locate(high[slot], low[slot], nextHigh, nextLow, nextOccupied);
            nextHigh[target] = high[slot];
            nextLow[target] = low[slot];
            next0[target] = fingerprint0[slot];
            next1[target] = fingerprint1[slot];
            next2[target] = fingerprint2[slot];
            next3[target] = fingerprint3[slot];
            nextOccupied[target] = 1;
        }
        high = nextHigh;
        low = nextLow;
        fingerprint0 = next0;
        fingerprint1 = next1;
        fingerprint2 = next2;
        fingerprint3 = next3;
        occupied = nextOccupied;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        high = low = fingerprint0 = fingerprint1 = fingerprint2 = fingerprint3 = null;
        occupied = null;
    }
}
