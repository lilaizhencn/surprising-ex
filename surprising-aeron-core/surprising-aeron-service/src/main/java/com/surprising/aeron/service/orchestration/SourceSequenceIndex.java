package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandSource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-confined primitive source sequence index; Map materialization is limited to snapshots. */
final class SourceSequenceIndex {
    private static final float LOAD_FACTOR = 0.70f;
    private int[] sourceCodes;
    private long[] sourceIds;
    private long[] sequences;
    private TradingCoreRuntime.SourceKey[] keys;
    private boolean[] occupied;
    private int[] insertionSourceCodes;
    private long[] insertionSourceIds;
    private int size;
    private int insertionCount;
    private TradingCoreRuntime.SourceKey lastLookupKey;

    SourceSequenceIndex(Map<TradingCoreRuntime.SourceKey, Long> initial) {
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

    int size() { return size; }

    long lookupOrDefault(CommandSource source, long sourceId, long defaultValue) {
        int slot = find(source, sourceId);
        lastLookupKey = slot < 0 ? null : keys[slot];
        return slot < 0 ? defaultValue : sequences[slot];
    }

    TradingCoreRuntime.SourceKey lastLookupKey() { return lastLookupKey; }

    void put(CommandSource source, long sourceId, long sequence) {
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

    void put(TradingCoreRuntime.SourceKey key, long sequence) {
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

    Map<TradingCoreRuntime.SourceKey, Long> snapshot() {
        LinkedHashMap<TradingCoreRuntime.SourceKey, Long> result = new LinkedHashMap<>(Math.max(1, size));
        for (int index = 0; index < insertionCount; index++) {
            CommandSource source = CommandSource.fromWireCode(insertionSourceCodes[index]);
            long sourceId = insertionSourceIds[index];
            result.put(keys[find(source, sourceId)], sequenceAt(source, sourceId));
        }
        return result;
    }

    long digest() {
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
