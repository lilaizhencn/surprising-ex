package com.surprising.aeron.service.orchestration.result;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owner 独占的来源序号表，用来源种类和来源 ID 定位已记录序号。原始数值数组承担查询，首次出现顺序单独记录供快照导出；写入只校验非负，不保证新序号大于旧值，命令顺序规则由外层执行。
 */
public final class SourceSequenceIndex {
    /** 来源表扩容阈值百分之七十。 */
    private static final float LOAD_FACTOR = 0.70f;
    /** 各占用槽的来源 wire code。 */
    private int[] sourceCodes;
    /** 各占用槽的来源实例 ID。 */
    private long[] sourceIds;
    /** 各占用来源当前保存的序号。 */
    private long[] sequences;
    /** 每个占用槽对应的规范 SourceKey 对象，更新和扩表时复用。 */
    private TradingCoreRuntime.SourceKey[] keys;
    /** 区分空槽与合法零数值的占用标记。 */
    private boolean[] occupied;
    /** 按首次出现顺序保存的来源代码。 */
    private int[] insertionSourceCodes;
    /** 按首次出现顺序保存的来源 ID，与来源代码数组配对。 */
    private long[] insertionSourceIds;
    /** 不同来源身份的当前数量。 */
    private int size;
    /** 首次出现顺序数组中有效来源条数。 */
    private int insertionCount;
    /** 最近一次 lookupOrDefault 命中的规范键，未命中时为 null。 */
    private TradingCoreRuntime.SourceKey lastLookupKey;

    /**
     * 按初始条数选择二次幂表容量，建立数组后依次导入已有来源；初始集合为空引用时建立空表。
     * @param initial 按原顺序导入的可空来源序号集合
     */
    public SourceSequenceIndex(Map<TradingCoreRuntime.SourceKey, Long> initial) {
        // 初始集合内的来源数量，空集合按零计算
        int expected = initial == null ? 0 : initial.size();
        // 按负载阈值选择的初始二次幂容量
        int capacity = 16;
        while (capacity * LOAD_FACTOR < Math.max(1, expected)) capacity <<= 1;
        allocate(capacity);
        if (initial != null) {
            for (Map.Entry<TradingCoreRuntime.SourceKey, Long> /* 当前导入的来源键和序号项 */ entry : initial.entrySet()) {
                // 初始有序集合中的规范来源键
                TradingCoreRuntime.SourceKey key = entry.getKey();
                put(key.source(), key.sourceId(), entry.getValue());
            }
        }
    }

    /**
     * 返回当前不同来源身份的数量，不是接收命令总数。
     */
    public int size() { return size; }

    /**
     * 按非空来源和来源 ID 查询序号，未命中返回传入默认值；同时更新最近查找的规范 SourceKey 引用，未命中将它清空。
     * @param source 非空命令来源种类
     * @param sourceId 该来源种类下的实例 ID
     * @param defaultValue 该来源未记录时返回的默认序号
     */
    public long lookupOrDefault(CommandSource source, long sourceId, long defaultValue) {
        // 当前查询、新增或重建来源对应的表槽
        int slot = find(source, sourceId);
        lastLookupKey = slot < 0 ? null : keys[slot];
        return slot < 0 ? defaultValue : sequences[slot];
    }

    /**
     * 借出最近一次 lookupOrDefault 命中的来源键；put 本身不刷新这份查找结果。
     */
    public TradingCoreRuntime.SourceKey lastLookupKey() { return lastLookupKey; }

    /**
     * 校验来源非空和序号非负；已有键直接覆盖值，新键按负载扩表后登记规范键和首次出现顺序，不做递增比较。
     * @param source 非空命令来源种类
     * @param sourceId 该来源种类下的实例 ID
     * @param sequence 要保存的非负序号，本索引不比较新旧大小
     */
    public void put(CommandSource source, long sourceId, long sequence) {
        if (source == null || sequence < 0) throw new IllegalArgumentException("invalid source sequence");
        // 当前查询、新增或重建来源对应的表槽
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

    /**
     * 使用调用方已有来源键写入，命中只更新序号，新键保留原键对象并追加首次出现顺序。
     * @param key 已有规范来源键，不能为空
     * @param sequence 要保存的非负序号，本索引不比较新旧大小
     */
    public void put(TradingCoreRuntime.SourceKey key, long sequence) {
        if (key == null) throw new IllegalArgumentException("source key is required");
        if (sequence < 0) throw new IllegalArgumentException("invalid source sequence");
        // 当前查询、新增或重建来源对应的表槽
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

    /**
     * 按来源首次出现顺序创建独立可修改 LinkedHashMap；更新已有来源不会改变它在导出中的位置。
     */
    public Map<TradingCoreRuntime.SourceKey, Long> snapshot() {
        // 按首次出现顺序物化的独立快照 Map
        LinkedHashMap<TradingCoreRuntime.SourceKey, Long> result = new LinkedHashMap<>(Math.max(1, size));
        for (/* 首次出现数组或旧表的遍历下标 */ int index = 0; index < insertionCount; index++) {
            // 当前导出、摘要或重建的来源种类
            CommandSource source = CommandSource.fromWireCode(insertionSourceCodes[index]);
            // 与当前顺序项配对的来源实例 ID
            long sourceId = insertionSourceIds[index];
            result.put(keys[find(source, sourceId)], sequenceAt(source, sourceId));
        }
        return result;
    }

    /**
     * 按现有全部来源计算并异或序号摘要；只读取权威序号数组，不维护另一份序号状态。
     */
    public long digest() {
        // 已遍历来源的异或摘要
        long digest = 0;
        for (/* 首次出现数组或旧表的遍历下标 */ int index = 0; index < insertionCount; index++) {
            // 当前导出、摘要或重建的来源种类
            CommandSource source = CommandSource.fromWireCode(insertionSourceCodes[index]);
            // 与当前顺序项配对的来源实例 ID
            long sourceId = insertionSourceIds[index];
            digest ^= TradingCoreRuntime.sourceSequenceDigest(
                    keys[find(source, sourceId)], sequenceAt(source, sourceId));
        }
        return digest;
    }

    /**
     * 由来源 wire code 和 ID 哈希后线性探测，命中返回槽号，遇空槽返回负一；调用方负责传入非空来源。
     * @param source 非空命令来源种类
     * @param sourceId 该来源种类下的实例 ID
     */
    private int find(CommandSource source, long sourceId) {
        // 当前查询数组容量减一的二次幂下标掩码
        int mask = occupied.length - 1;
        // 当前查询、新增或重建来源对应的表槽
        int slot = hash(source.wireCode(), sourceId) & mask;
        while (occupied[slot]) {
            if (sourceCodes[slot] == source.wireCode() && sourceIds[slot] == sourceId) return slot;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    /**
     * 取得必定已存在来源的当前序号，内部调用方先保证该键在表中。
     * @param source 非空命令来源种类
     * @param sourceId 该来源种类下的实例 ID
     */
    private long sequenceAt(CommandSource source, long sourceId) {
        return sequences[find(source, sourceId)];
    }

    /**
     * 从来源哈希起点探测到空槽，用于已确认未存在的新键或重建表。
     * @param source 非空命令来源种类
     * @param sourceId 该来源种类下的实例 ID
     */
    private int insertionSlot(CommandSource source, long sourceId) {
        // 当前查询数组容量减一的二次幂下标掩码
        int mask = occupied.length - 1;
        // 当前查询、新增或重建来源对应的表槽
        int slot = hash(source.wireCode(), sourceId) & mask;
        while (occupied[slot]) slot = (slot + 1) & mask;
        return slot;
    }

    /**
     * 把查询数组扩为两倍并重新散列占用项，复用原 SourceKey；已有来源数和首次出现顺序数组保持原含义。
     */
    private void resize() {
        // 扩表前来源代码数组
        int[] oldSources = sourceCodes;
        // 扩表前来源 ID 数组
        long[] oldIds = sourceIds;
        // 扩表前权威序号数组
        long[] oldSequences = sequences;
        // 扩表前规范键对象数组，重建后继续复用
        TradingCoreRuntime.SourceKey[] oldKeys = keys;
        // 扩表前占用标记，只有占用项需要重建
        boolean[] oldOccupied = occupied;
        allocate(oldSources.length << 1);
        for (/* 首次出现数组或旧表的遍历下标 */ int index = 0; index < oldSources.length; index++) {
            if (!oldOccupied[index]) continue;
            // 当前导出、摘要或重建的来源种类
            CommandSource source = CommandSource.fromWireCode(oldSources[index]);
            // 当前查询、新增或重建来源对应的表槽
            int slot = insertionSlot(source, oldIds[index]);
            occupied[slot] = true;
            sourceCodes[slot] = oldSources[index];
            sourceIds[slot] = oldIds[index];
            sequences[slot] = oldSequences[index];
            keys[slot] = oldKeys[index];
        }
    }

    /**
     * 分配查询数组，首次建表时同时分配来源顺序数组；重建时不清已有顺序和计数。
     * @param capacity 查询数组的新容量，由内部二次幂增长流程提供
     */
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

    /**
     * 为逐项追加首次出现的来源预留容量，不足时把两组顺序数组各扩一倍。
     * @param required 下一条来源追加后需要的顺序容量
     */
    private void ensureInsertionCapacity(int required) {
        if (required <= insertionSourceCodes.length) return;
        // 一次翻倍后的来源顺序数组容量
        int next = insertionSourceCodes.length << 1;
        insertionSourceCodes = java.util.Arrays.copyOf(insertionSourceCodes, next);
        insertionSourceIds = java.util.Arrays.copyOf(insertionSourceIds, next);
    }

    /**
     * 混合来源代码与来源 ID，产生线性探测使用的起点哈希值。
     * @param sourceCode 来源 wire code 数值
     * @param sourceId 该来源种类下的实例 ID
     */
    private static int hash(int sourceCode, long sourceId) {
        // 来源代码与 ID 混合后的中间哈希值
        long value = sourceId ^ (sourceCode * 0x9E3779B97F4A7C15L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) value;
    }
}
