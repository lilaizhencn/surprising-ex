package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.experimental.Accessors;

/** Owner 独占的来源序号表，用来源种类和来源 ID 定位已记录序号。原始数值数组承担查询，首次出现顺序单独记录供快照导出。写入只校验非负，不保证新序号大于旧值，命令顺序规则由外层执行。 */
public final class SourceSequenceIndex {
    private static final float LOAD_FACTOR = 0.70f;
    private int[] sourceCodes;
    private long[] sourceIds;
    private long[] sequences;
    private TradingCoreRuntime.SourceKey[] keys;
    private boolean[] occupied;
    /** 按首次出现顺序保存的来源代码。 */
    private int[] insertionSourceCodes;
    /** 按首次出现顺序保存的来源 ID，与来源代码数组配对。 */
    private long[] insertionSourceIds;
    @Accessors(fluent = true, chain = false)
    @Getter
    private int size;
    /** 首次出现顺序数组中有效来源条数。 */
    private int insertionCount;
    @Accessors(fluent = true, chain = false)
    @Getter
    private TradingCoreRuntime.SourceKey lastLookupKey;

    /** 按初始条数选择二次幂表容量，建立数组后依次导入已有来源。 */
    public SourceSequenceIndex(Map<TradingCoreRuntime.SourceKey, Long> initial) {
        int expected = initial == null ? 0 : initial.size();
        int capacity = 16;
        while (capacity * LOAD_FACTOR < Math.max(1, expected)) capacity <<= 1;
        allocate(capacity);
        if (initial != null) {
            for (Map.Entry<TradingCoreRuntime.SourceKey, Long> entry : initial.entrySet()) {
                TradingCoreRuntime.SourceKey key = entry.getKey();
                put(key.source(), key.sourceId(), entry.getValue());
            }
        }
    }

    /** 按非空来源和来源 ID 查询序号，未命中返回传入默认值。 */
    public long lookupOrDefault(CommandSource source, long sourceId, long defaultValue) {
        int slot = find(source, sourceId);
        lastLookupKey = slot < 0 ? null : keys[slot];
        return slot < 0 ? defaultValue : sequences[slot];
    }

    /** 校验来源非空和序号非负。已有键直接覆盖值，新键按负载扩表后登记规范键和首次出现顺序，不做递增比较。 */
    public void put(CommandSource source, long sourceId, long sequence) {
        if (source == null || sequence < 0) throw new IllegalArgumentException("invalid source sequence");
        int slot = find(source, sourceId);
        if (slot >= 0) {
            sequences[slot] = sequence;
            return;
        }
        if ((size + 1) > sourceCodes.length * LOAD_FACTOR) {
            resize();
            slot = find(source, sourceId);
        }
        slot = insertionSlot(source, sourceId);
        occupied[slot] = true;
        sourceCodes[slot] = source.wireCode();
        sourceIds[slot] = sourceId;
        sequences[slot] = sequence;
        keys[slot] = new TradingCoreRuntime.SourceKey(source, sourceId);
        size++;
        ensureInsertionCapacity(insertionCount + 1);
        insertionSourceCodes[insertionCount] = source.wireCode();
        insertionSourceIds[insertionCount++] = sourceId;
    }

    /** 使用调用方已有来源键写入，命中只更新序号，新键保留原键对象并追加首次出现顺序。 */
    public void put(TradingCoreRuntime.SourceKey key, long sequence) {
        if (key == null) throw new IllegalArgumentException("source key is required");
        if (sequence < 0) throw new IllegalArgumentException("invalid source sequence");
        int slot = find(key.source(), key.sourceId());
        if (slot >= 0) {
            sequences[slot] = sequence;
            return;
        }
        if ((size + 1) > sourceCodes.length * LOAD_FACTOR) resize();
        slot = insertionSlot(key.source(), key.sourceId());
        occupied[slot] = true;
        sourceCodes[slot] = key.source().wireCode();
        sourceIds[slot] = key.sourceId();
        sequences[slot] = sequence;
        keys[slot] = key;
        size++;
        ensureInsertionCapacity(insertionCount + 1);
        insertionSourceCodes[insertionCount] = key.source().wireCode();
        insertionSourceIds[insertionCount++] = key.sourceId();
    }

    /** 按来源首次出现顺序创建独立可修改 LinkedHashMap。 */
    public Map<TradingCoreRuntime.SourceKey, Long> snapshot() {
        LinkedHashMap<TradingCoreRuntime.SourceKey, Long> result = new LinkedHashMap<>(Math.max(1, size));
        for (int index = 0; index < insertionCount; index++) {
            CommandSource source = CommandSource.fromWireCode(insertionSourceCodes[index]);
            long sourceId = insertionSourceIds[index];
            result.put(keys[find(source, sourceId)], sequenceAt(source, sourceId));
        }
        return result;
    }

    /** 按现有全部来源计算并异或序号摘要。 */
    public long digest() {
        long digest = 0;
        for (int index = 0; index < insertionCount; index++) {
            CommandSource source = CommandSource.fromWireCode(insertionSourceCodes[index]);
            long sourceId = insertionSourceIds[index];
            digest ^= TradingCoreRuntime.sourceSequenceDigest(
                    keys[find(source, sourceId)], sequenceAt(source, sourceId));
        }
        return digest;
    }

    private int find(CommandSource source, long sourceId) {
        int mask = occupied.length - 1;
        int slot = hash(source.wireCode(), sourceId) & mask;
        while (occupied[slot]) {
            if (sourceCodes[slot] == source.wireCode() && sourceIds[slot] == sourceId) return slot;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private long sequenceAt(CommandSource source, long sourceId) {
        return sequences[find(source, sourceId)];
    }

    private int insertionSlot(CommandSource source, long sourceId) {
        int mask = occupied.length - 1;
        int slot = hash(source.wireCode(), sourceId) & mask;
        while (occupied[slot]) slot = (slot + 1) & mask;
        return slot;
    }

    /** 已有来源数和首次出现顺序数组保持原含义。 */
    private void resize() {
        int[] oldSources = sourceCodes;
        long[] oldIds = sourceIds;
        long[] oldSequences = sequences;
        TradingCoreRuntime.SourceKey[] oldKeys = keys;
        boolean[] oldOccupied = occupied;
        allocate(oldSources.length << 1);
        for (int index = 0; index < oldSources.length; index++) {
            if (!oldOccupied[index]) continue;
            CommandSource source = CommandSource.fromWireCode(oldSources[index]);
            int slot = insertionSlot(source, oldIds[index]);
            occupied[slot] = true;
            sourceCodes[slot] = oldSources[index];
            sourceIds[slot] = oldIds[index];
            sequences[slot] = oldSequences[index];
            keys[slot] = oldKeys[index];
        }
    }

    /** 分配查询数组，首次建表时同时分配来源顺序数组。重建时不清已有顺序和计数。 */
    private void allocate(int capacity) {
        sourceCodes = new int[capacity];
        sourceIds = new long[capacity];
        sequences = new long[capacity];
        keys = new TradingCoreRuntime.SourceKey[capacity];
        occupied = new boolean[capacity];
        if (insertionSourceCodes == null) {
            insertionSourceCodes = new int[Math.max(8, capacity >>> 1)];
            insertionSourceIds = new long[insertionSourceCodes.length];
        }
    }

    /** 为逐项追加首次出现的来源预留容量，不足时把两组顺序数组各扩一倍。 */
    private void ensureInsertionCapacity(int required) {
        if (required <= insertionSourceCodes.length) return;
        int next = insertionSourceCodes.length << 1;
        insertionSourceCodes = java.util.Arrays.copyOf(insertionSourceCodes, next);
        insertionSourceIds = java.util.Arrays.copyOf(insertionSourceIds, next);
    }

    private static int hash(int sourceCode, long sourceId) {
        long value = sourceId ^ (sourceCode * 0x9E3779B97F4A7C15L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }
}
