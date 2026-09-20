package com.surprising.aeron.service.orchestration;

import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 命令幂等结果账本：独占保留顺序、字节上限和结果摘要；仅 owner 读写。 */
final class CommandResultLedger {
    /* 命令结果及其保留顺序；不是第二份订单或账户状态。 */
    /** Keep the probe table below half load while retaining at most 128 results. */
    private static final int TABLE_CAPACITY = 512;
    private static final int TABLE_MASK = TABLE_CAPACITY - 1;
    private static final int RETENTION_CAPACITY = 256;
    private final long[] commandIdMost = new long[TABLE_CAPACITY];
    private final long[] commandIdLeast = new long[TABLE_CAPACITY];
    private final StoredResult[] results = new StoredResult[TABLE_CAPACITY];
    /** Preconstructed commit records; a terminal command reuses one ring record. */
    private final StoredResult[] recordRing = new StoredResult[TABLE_CAPACITY];
    private final int[] freeRecordSlots = new int[TABLE_CAPACITY];
    private int freeRecordCount;
    /** Retention order; eviction advances this queue instead of scanning the hash table. */
    private final int[] retentionSlots = new int[RETENTION_CAPACITY];
    private final int[] retentionPositions = new int[TABLE_CAPACITY];
    private final ResponseArena responseArena;
    private int retentionHead;
    private int retentionTail;
    private int size;
    /** 当前保留结果的协议字节总量，用于执行内存上限。 */
    private long commandResultBytes;
    /** 下一条新结果的保留序号；更新旧结果保持原序号。 */
    private long nextResultRetentionSequence;

    CommandResultLedger(LinkedHashMap<UUID, StoredResult> results) {
        this(results, null);
    }

    CommandResultLedger(LinkedHashMap<UUID, StoredResult> results, ResponseArena responseArena) {
        this.responseArena = responseArena;
        for (int index = 0; index < TABLE_CAPACITY; index++) {
            recordRing[index] = new StoredResult(index);
            freeRecordSlots[index] = index;
        }
        freeRecordCount = TABLE_CAPACITY;
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

    /**
     * Returns the deterministic response for a retained command, or {@code null}
     * when this command ID has not been retained.  Keeping this decision beside
     * the primitive ledger leaves the Owner command path free of result-record
     * field plumbing.
     */
    CoreResponse duplicateResponse(StoredResult duplicate, CommandFingerprint fingerprint,
                                   long appliedCommandCount) {
        if (!duplicate.fingerprint().equals(fingerprint)) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, EMPTY_RESPONSE_DATA);
        }
        if (responseArena != null) responseArena.retain(duplicate.responseDataUnsafe());
        return CoreResponse.owned(ResponseStatus.DUPLICATE,
                duplicate.status(), duplicate.resultCode(), duplicate.appliedCommandCount(),
                duplicate.responseDataUnsafe(),
                duplicate.responseDataOffsetUnsafe(), duplicate.responseDataLength());
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
        return Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataLength());
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
        digest = mix(digest, result.retentionSequence());
        byte[] response = result.responseDataUnsafe();
        int end = result.responseDataOffsetUnsafe() + result.responseDataLength();
        for (int index = result.responseDataOffsetUnsafe(); index < end; index++) {
            digest = mix(digest, Byte.toUnsignedInt(response[index]));
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
                result.appliedCommandCount,
                result.responseData, result.responseOffset, result.responseLength);
    }

    /** 在保留序号确定之后只构造一次不可变结果；调用方转移响应字节所有权。 */
    void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData) {
        storeOwnedResult(commandId, fingerprint, status, resultCode, appliedCommandCount,
                responseData, 0,
                responseData == null ? 0 : responseData.length);
    }

    /** Stores a stable response slice without copying arena-backed storage. */
    void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength) {
        java.util.Objects.requireNonNull(commandId, "command id");
        if (responseData == null) {
            responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
            responseOffset = 0;
            responseLength = 0;
        } else if (responseOffset < 0 || responseLength < 0
                || responseOffset > responseData.length - responseLength) {
            throw new IllegalArgumentException("invalid result response slice");
        }
        long resultBytes = Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH,
                responseLength);
        if (resultBytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger entry exceeds byte bound");
        }
        int slot = locate(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits());
        StoredResult previous = results[slot];
        long retentionSequence = previous == null ? nextResultRetentionSequence : previous.retentionSequence();
        byte[] previousResponse = previous == null ? null : previous.responseDataUnsafe();
        long previousBytes = previous == null ? 0 : resultEntryBytes(previous);
        if (freeRecordCount == 0) throw new IllegalStateException("result commit ring is full");
        // The terminal response is still owned by the Owner command/output FIFO after this
        // method returns. Keep a separate arena reference for the idempotency ledger so eviction
        // cannot recycle bytes that have not reached the transport yet.
        if (responseArena != null) {
            if (previous != null && previousResponse == responseData) {
                // Replacing the same command result keeps its existing ledger reference, but the
                // newly returned CoreResponse still needs its own transport reference. This is
                // also the path used when a retained result is queried after its first response
                // has already drained from the egress queue.
                responseArena.retain(responseData);
            } else {
                responseArena.retainLedger(responseData);
            }
        }
        // A replacement gets a different preconstructed record.  Existing snapshots and
        // duplicate readers may still hold the previous record object, so mutating it in place
        // would break the ledger's historical identity contract even though the table slot is
        // replaced atomically.
        StoredResult retained = recordRing[freeRecordSlots[--freeRecordCount]];
        retained.set(fingerprint, status, resultCode, appliedCommandCount,
                responseData, responseOffset, responseLength, retentionSequence, true);
        long nextSequence = previous == null ? Math.incrementExact(nextResultRetentionSequence)
                : nextResultRetentionSequence;
        if (previous == null) {
            commandIdMost[slot] = commandId.getMostSignificantBits();
            commandIdLeast[slot] = commandId.getLeastSignificantBits();
            enqueueRetentionSlot(slot);
            size++;
        } else if (responseArena != null && previousResponse != responseData) {
            responseArena.releaseLedger(previousResponse);
        }
        if (previous != null) freeRecordSlots[freeRecordCount++] = previous.ringSlot;
        results[slot] = retained;
        nextResultRetentionSequence = nextSequence;
        commandResultBytes = Math.addExact(commandResultBytes - previousBytes, resultBytes);
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
        if (freeRecordCount == 0) throw new IllegalStateException("result commit ring is full");
        StoredResult retained = recordRing[freeRecordSlots[--freeRecordCount]];
        retained.copyFrom(value);
        commandIdMost[slot] = id.getMostSignificantBits();
        commandIdLeast[slot] = id.getLeastSignificantBits();
        results[slot] = retained;
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
        StoredResult removed = results[slot];
        if (responseArena != null && removed != null) {
            responseArena.releaseLedger(removed.responseDataUnsafe());
        }
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
        freeRecordSlots[freeRecordCount++] = removed.ringSlot;
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
        private final int ringSlot;
        /** 不可变命令指纹，防止相同命令 ID 对应不同请求内容。 */
        private CommandFingerprint fingerprint;
        /** 该命令或业务项的执行状态。 */
        private ResponseStatus status;
        /** 该命令或业务项的确定性结果码。 */
        private CoreResultCode resultCode;
        /** 已接收并应用的命令序号上界；可能高于已完成结算水位。 */
        private long appliedCommandCount;
        /** 已保留的协议响应字节；所有权转移或复制后保存。 */
        private byte[] responseData;
        /** Offset and logical length for an arena-backed response slice. */
        private int responseOffset;
        private int responseLength;
        /** 本结果的保留顺序，替换内容不会重新排到队尾。 */
        private long retentionSequence;
        /** 缓存摘要所对应的命令 ID。 */
        UUID digestCommandId;
        /** 本条结果的摘要缓存，避免恢复遍历重复计算。 */
        long cachedEntryDigest;

        private StoredResult(int ringSlot) {
            this.ringSlot = ringSlot;
            fingerprint = null;
            status = null;
            resultCode = null;
            responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
        }

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, retentionSequence, false);
        }

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence, boolean ownedResponseData) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, 0, responseData == null ? 0 : responseData.length, retentionSequence,
                    ownedResponseData);
        }

        StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, int responseOffset, int responseLength,
                     long retentionSequence, boolean ownedResponseData) {
            this.ringSlot = -1;
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || retentionSequence < 0 || responseOffset < 0
                    || responseLength < 0 || responseData == null
                    && (responseOffset != 0 || responseLength != 0) || responseData != null
                    && responseOffset > responseData.length - responseLength) {
                throw new IllegalArgumentException("invalid stored result");
            }
            set(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, responseOffset, responseLength, retentionSequence, ownedResponseData);
        }

        private void set(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                          long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength,
                          long retentionSequence, boolean ownedResponseData) {
            if (fingerprint == null || status == null || resultCode == null || appliedCommandCount < 0
                    || retentionSequence < 0 || responseOffset < 0
                    || responseLength < 0 || responseData == null
                    && (responseOffset != 0 || responseLength != 0) || responseData != null
                    && responseOffset > responseData.length - responseLength) {
                throw new IllegalArgumentException("invalid stored result");
            }
            this.fingerprint = fingerprint;
            this.status = status;
            this.resultCode = resultCode;
            this.appliedCommandCount = appliedCommandCount;
            byte[] normalized = responseData == null ? TradingCoreRuntime.EMPTY_RESPONSE_DATA : responseData;
            if (normalized == TradingCoreRuntime.EMPTY_RESPONSE_DATA || responseLength == 0) {
                this.responseData = TradingCoreRuntime.EMPTY_RESPONSE_DATA;
                this.responseOffset = 0;
                this.responseLength = 0;
            } else if (ownedResponseData) {
                this.responseData = normalized;
                this.responseOffset = responseOffset;
                this.responseLength = responseLength;
            } else {
                this.responseData = java.util.Arrays.copyOfRange(normalized, responseOffset,
                        responseOffset + responseLength);
                this.responseOffset = 0;
                this.responseLength = responseLength;
            }
            this.retentionSequence = retentionSequence;
            this.digestCommandId = null;
            this.cachedEntryDigest = 0;
        }

        private void copyFrom(StoredResult other) {
            set(other.fingerprint, other.status, other.resultCode, other.appliedCommandCount,
                    other.responseData, other.responseOffset,
                    other.responseLength, other.retentionSequence, true);
        }

        static StoredResult owned(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                                  long appliedCommandCount,
                                  byte[] responseData) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, 0, true);
        }

        StoredResult withRetentionSequence(long sequence) {
            return new StoredResult(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, responseOffset, responseLength,
                    sequence, true);
        }

        CommandFingerprint fingerprint() { return fingerprint; }
        ResponseStatus status() { return status; }
        CoreResultCode resultCode() { return resultCode; }
        long appliedCommandCount() { return appliedCommandCount; }
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

        int responseDataOffsetUnsafe() { return responseOffset; }
        int responseDataLength() { return responseLength; }

        public byte[] responseData() {
            return responseLength == 0 ? TradingCoreRuntime.EMPTY_RESPONSE_DATA
                    : java.util.Arrays.copyOfRange(responseData, responseOffset, responseOffset + responseLength);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof StoredResult result)) return false;
            return appliedCommandCount == result.appliedCommandCount
                    && retentionSequence == result.retentionSequence
                    && fingerprint.equals(result.fingerprint)
                    && status == result.status
                    && resultCode == result.resultCode
                    && responseBytesEqual(result);
        }

        @Override
        public int hashCode() {
            int hash = java.util.Objects.hash(fingerprint, status, resultCode, appliedCommandCount,
                    retentionSequence);
            int responseHash = 1;
            for (int index = responseOffset; index < responseOffset + responseLength; index++)
                responseHash = 31 * responseHash + responseData[index];
            return 31 * hash + responseHash;
        }

        private boolean responseBytesEqual(StoredResult other) {
            if (responseLength != other.responseLength) return false;
            for (int index = 0; index < responseLength; index++) {
                if (responseData[responseOffset + index] != other.responseData[other.responseOffset + index]) return false;
            }
            return true;
        }
    }
}
