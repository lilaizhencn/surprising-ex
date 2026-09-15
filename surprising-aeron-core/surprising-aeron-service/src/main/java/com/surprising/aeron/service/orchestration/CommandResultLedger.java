package com.surprising.aeron.service.orchestration;

import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 命令幂等结果账本：独占保留顺序、字节上限和结果摘要；仅 owner 读写。 */
final class CommandResultLedger {
    /** 命令结果及其保留顺序；不是第二份订单或账户状态。 */
    /** Keep the probe table below half load while retaining at most 128 results. */
    private static final int TABLE_CAPACITY = 512;
    private static final int TABLE_MASK = TABLE_CAPACITY - 1;
    private static final int RETENTION_CAPACITY = 256;
    private final long[] commandIdMost = new long[TABLE_CAPACITY];
    private final long[] commandIdLeast = new long[TABLE_CAPACITY];
    private final StoredResult[] results = new StoredResult[TABLE_CAPACITY];
    /** Retention order; eviction advances this queue instead of scanning the hash table. */
    private final int[] retentionSlots = new int[RETENTION_CAPACITY];
    private final int[] retentionPositions = new int[TABLE_CAPACITY];
    private int retentionHead;
    private int retentionTail;
    private int size;
    /** 当前保留结果的协议字节总量，用于执行内存上限。 */
    private long commandResultBytes;
    /** 下一条新结果的保留序号；更新旧结果保持原序号。 */
    private long nextResultRetentionSequence;

    CommandResultLedger(LinkedHashMap<UUID, StoredResult> results) {
        java.util.Arrays.fill(retentionPositions, -1);
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger");
        }
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) put(entry.getKey(), entry.getValue());
        commandResultBytes = resultLedgerBytes(results);
        nextResultRetentionSequence = nextRetentionSequence(results);
    }

    StoredResult get(UUID id) {
        if (id == null) return null;
        return results[locate(id.getMostSignificantBits(), id.getLeastSignificantBits())];
    }

    /** Snapshot-only materialization; no Map node is created on the command hot path. */
    Map<UUID, StoredResult> entries() {
        ArrayList<Integer> slots = new ArrayList<>(size);
        for (int slot = 0; slot < TABLE_CAPACITY; slot++) if (results[slot] != null) slots.add(slot);
        slots.sort(Comparator.comparingLong(slot -> results[slot].retentionSequence()));
        LinkedHashMap<UUID, StoredResult> snapshot = new LinkedHashMap<>(Math.max(4, size * 2));
        for (int slot : slots) snapshot.put(new UUID(commandIdMost[slot], commandIdLeast[slot]), results[slot]);
        return Collections.unmodifiableMap(snapshot);
    }

    static long resultLedgerBytes(Map<UUID, StoredResult> results) {
        long bytes = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            bytes = Math.addExact(bytes, resultEntryBytes(entry.getValue()));
        }
        return bytes;
    }

    static long resultEntryBytes(StoredResult result) {
        return Math.addExact(CoreStateSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataUnsafe().length);
    }

    static long computeResultEntryDigest(UUID commandId, StoredResult result) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, commandId.getMostSignificantBits());
        digest = mix(digest, commandId.getLeastSignificantBits());
        for (int index = 0; index < CommandFingerprint.LENGTH; index++) {
            digest = mix(digest, Byte.toUnsignedInt(result.fingerprint().byteAt(index)));
        }
        digest = mix(digest, result.status().wireCode());
        digest = mix(digest, result.resultCode().wireCode());
        digest = mix(digest, result.appliedCommandCount());
        digest = mix(digest, result.requiredExportSequence());
        digest = mix(digest, result.retentionSequence());
        for (byte value : result.responseDataUnsafe()) {
            digest = mix(digest, Byte.toUnsignedInt(value));
        }
        return digest;
    }

    static long nextRetentionSequence(Map<UUID, StoredResult> results) {
        long next = 1;
        for (StoredResult result : results.values()) {
            next = Math.max(next, Math.incrementExact(result.retentionSequence()));
        }
        return next;
    }

    static void validateResultLedger(Map<UUID, StoredResult> results) {
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger count");
        }
        long bytes = resultLedgerBytes(results);
        long previousRetentionSequence = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().retentionSequence() <= previousRetentionSequence) {
                throw new IllegalArgumentException("invalid result ledger retention metadata");
            }
            previousRetentionSequence = entry.getValue().retentionSequence();
        }
        if (bytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger exceeds byte bound");
        }
    }

    void storeResult(UUID commandId, StoredResult result) {
        storeOwnedResult(commandId, result.fingerprint, result.status, result.resultCode,
                result.appliedCommandCount, result.requiredExportSequence, result.stateHash, result.responseData);
    }

    /** 在保留序号确定之后只构造一次不可变结果；调用方转移响应字节所有权。 */
    void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount, long requiredExportSequence,
                          long stateHash, byte[] responseData) {
        java.util.Objects.requireNonNull(commandId, "command id");
        long resultBytes = Math.addExact(CoreStateSnapshotCodec.RESULT_FIXED_LENGTH,
                responseData == null ? 0 : responseData.length);
        if (resultBytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger entry exceeds byte bound");
        }
        int slot = locate(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
        StoredResult previous = results[slot];
        long retentionSequence = previous == null ? nextResultRetentionSequence : previous.retentionSequence();
        StoredResult retained = new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData, retentionSequence, true);
        long nextSequence = previous == null ? Math.incrementExact(nextResultRetentionSequence)
                : nextResultRetentionSequence;
        long nextBytes = Math.addExact(commandResultBytes - (previous == null ? 0 : resultEntryBytes(previous)),
                resultBytes);
        if (previous == null) {
            commandIdMost[slot] = commandId.getMostSignificantBits();
            commandIdLeast[slot] = commandId.getLeastSignificantBits();
            enqueueRetentionSlot(slot);
            size++;
        }
        results[slot] = retained;
        nextResultRetentionSequence = nextSequence;
        commandResultBytes = nextBytes;
        while (size > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            int oldest = oldestSlot(commandId);
            if (oldest < 0) throw new IllegalStateException("result ledger protected entry exceeds bound");
            commandResultBytes = Math.subtractExact(commandResultBytes, resultEntryBytes(results[oldest]));
            remove(oldest);
        }
    }

    private void put(UUID id, StoredResult value) {
        int slot = locate(id.getMostSignificantBits(), id.getLeastSignificantBits());
        commandIdMost[slot] = id.getMostSignificantBits();
        commandIdLeast[slot] = id.getLeastSignificantBits();
        results[slot] = value;
        enqueueRetentionSlot(slot);
        size++;
    }

    /** One probe finds either the existing result or its empty insertion slot. */
    private int locate(long most, long least) {
        int slot = hash(most, least);
        while (results[slot] != null) {
            if (commandIdMost[slot] == most && commandIdLeast[slot] == least) return slot;
            slot = (slot + 1) & TABLE_MASK;
        }
        return slot;
    }

    private int oldestSlot(UUID protectedId) {
        long protectedMost = protectedId == null ? Long.MIN_VALUE : protectedId.getMostSignificantBits();
        long protectedLeast = protectedId == null ? Long.MIN_VALUE : protectedId.getLeastSignificantBits();
        for (int offset = 0; offset < RETENTION_CAPACITY && retentionHead + offset < retentionTail; offset++) {
            int slot = retentionSlots[(retentionHead + offset) & (RETENTION_CAPACITY - 1)];
            if (slot < 0 || results[slot] == null) continue;
            if (commandIdMost[slot] == protectedMost && commandIdLeast[slot] == protectedLeast) continue;
            return slot;
        }
        return -1;
    }

    private void remove(int slot) {
        int position = retentionPositions[slot];
        retentionSlots[position & (RETENTION_CAPACITY - 1)] = -1;
        retentionPositions[slot] = -1;
        size--;
        // Close the probe-chain hole instead of accumulating deleted markers. Retention
        // order follows the moved result, so eviction and snapshot order stay unchanged.
        int hole = slot;
        for (int next = (hole + 1) & TABLE_MASK; results[next] != null;
                next = (next + 1) & TABLE_MASK) {
            int home = hash(commandIdMost[next], commandIdLeast[next]);
            if (((hole - home) & TABLE_MASK) < ((next - home) & TABLE_MASK)) {
                commandIdMost[hole] = commandIdMost[next];
                commandIdLeast[hole] = commandIdLeast[next];
                results[hole] = results[next];
                int movedPosition = retentionPositions[next];
                retentionPositions[hole] = movedPosition;
                retentionSlots[movedPosition & (RETENTION_CAPACITY - 1)] = hole;
                hole = next;
            }
        }
        results[hole] = null;
        retentionPositions[hole] = -1;
        advanceRetentionHead();
    }

    private void enqueueRetentionSlot(int slot) {
        advanceRetentionHead();
        if (retentionTail - retentionHead >= RETENTION_CAPACITY) compactRetentionQueue();
        int position = retentionTail++;
        retentionSlots[position & (RETENTION_CAPACITY - 1)] = slot;
        retentionPositions[slot] = position;
    }

    private void advanceRetentionHead() {
        while (retentionHead < retentionTail
                && retentionSlots[retentionHead & (RETENTION_CAPACITY - 1)] < 0) {
            retentionHead++;
        }
    }

    /** Compact holes left when a retained old result grows and evicts newer entries. */
    private void compactRetentionQueue() {
        int count = 0;
        int pending = retentionTail - retentionHead;
        for (int offset = 0; offset < pending; offset++) {
            int slot = retentionSlots[(retentionHead + offset) & (RETENTION_CAPACITY - 1)];
            if (slot < 0) continue;
            int position = retentionHead + count++;
            retentionSlots[position & (RETENTION_CAPACITY - 1)] = slot;
            retentionPositions[slot] = position;
        }
        retentionTail = retentionHead + count;
    }

    private static int hash(long most, long least) {
        long value = most * 0x9E3779B97F4A7C15L + least;
        value ^= value >>> 33;
        value *= 0xC2B2AE3D27D4EB4FL;
        return (int) (value ^ (value >>> 32)) & TABLE_MASK;
    }

    static final class StoredResult {
        /** 不可变命令指纹，防止相同命令 ID 对应不同请求内容。 */
        final CommandFingerprint fingerprint;
        /** 该命令或业务项的执行状态。 */
        final ResponseStatus status;
        /** 该命令或业务项的确定性结果码。 */
        final CoreResultCode resultCode;
        /** 已接收并应用的命令序号上界；可能高于已完成结算水位。 */
        final long appliedCommandCount;
        /** 读取本结果对应状态所需的最小导出水位。 */
        final long requiredExportSequence;
        /** 结果对应的业务状态摘要，用于恢复核对。 */
        final long stateHash;
        /** 已保留的协议响应字节；所有权转移或复制后保存。 */
        final byte[] responseData;
        /** 本结果的保留顺序，替换内容不会重新排到队尾。 */
        final long retentionSequence;
        /** 缓存摘要所对应的命令 ID。 */
        UUID digestCommandId;
        /** 本条结果的摘要缓存，避免恢复遍历重复计算。 */
        long cachedEntryDigest;

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount, long requiredExportSequence, long stateHash,
                     byte[] responseData, long retentionSequence) {
            this(fingerprint, status, resultCode, appliedCommandCount, requiredExportSequence, stateHash,
                    responseData, retentionSequence, false);
        }

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                             long appliedCommandCount, long requiredExportSequence, long stateHash,
                             byte[] responseData, long retentionSequence, boolean ownedResponseData) {
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || requiredExportSequence < 0 || retentionSequence < 0) {
                throw new IllegalArgumentException("invalid stored result");
            }
            this.fingerprint = fingerprint;
            this.status = status;
            this.resultCode = resultCode;
            this.appliedCommandCount = appliedCommandCount;
            this.requiredExportSequence = requiredExportSequence;
            this.stateHash = stateHash;
            byte[] normalized = responseData == null ? TradingCoreRuntime.EMPTY_RESPONSE_DATA : responseData;
            // The shared empty payload is immutable, so it does not need a defensive clone.
            this.responseData = ownedResponseData || normalized == TradingCoreRuntime.EMPTY_RESPONSE_DATA
                    ? normalized : normalized.clone();
            this.retentionSequence = retentionSequence;
        }

        static StoredResult owned(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                                  long appliedCommandCount, long requiredExportSequence, long stateHash,
                                  byte[] responseData) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, responseData, 0, true);
        }

        StoredResult withRetentionSequence(long sequence) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, responseData, sequence, true);
        }

        CommandFingerprint fingerprint() { return fingerprint; }
        ResponseStatus status() { return status; }
        CoreResultCode resultCode() { return resultCode; }
        long appliedCommandCount() { return appliedCommandCount; }
        long requiredExportSequence() { return requiredExportSequence; }
        long stateHash() { return stateHash; }
        long retentionSequence() { return retentionSequence; }

        long entryDigest(UUID commandId) {
            if (commandId == null) throw new IllegalArgumentException("command id is required");
            if (commandId.equals(digestCommandId)) return cachedEntryDigest;
            long digest = computeResultEntryDigest(commandId, this);
            digestCommandId = commandId;
            cachedEntryDigest = digest;
            return digest;
        }

        byte[] responseDataUnsafe() {
            return responseData;
        }

        public byte[] responseData() {
            return responseData.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StoredResult result)) return false;
            return appliedCommandCount == result.appliedCommandCount
                    && requiredExportSequence == result.requiredExportSequence
                    && stateHash == result.stateHash
                    && retentionSequence == result.retentionSequence
                    && fingerprint.equals(result.fingerprint)
                    && status == result.status
                    && resultCode == result.resultCode
                    && java.util.Arrays.equals(responseData, result.responseData);
        }

        @Override
        public int hashCode() {
            int hash = java.util.Objects.hash(fingerprint, status, resultCode, appliedCommandCount,
                    requiredExportSequence, stateHash, retentionSequence);
            return 31 * hash + java.util.Arrays.hashCode(responseData);
        }
    }
}
