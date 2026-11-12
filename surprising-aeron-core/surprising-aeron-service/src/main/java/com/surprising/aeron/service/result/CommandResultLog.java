package com.surprising.aeron.service.result;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCore.*;
import static com.surprising.aeron.service.core.TradingCore.Limits.*;

/** 交易步骤 5–6：Owner 独占的近期命令结果账本，按 UUID 保存指纹、原终态和响应字节，用于重复命令及结果查询。条数和协议字节都有上限，替换保留原顺序。 */
public final class CommandResultLog {
    private static final int TABLE_CAPACITY = 512;
    private static final int TABLE_MASK = TABLE_CAPACITY - 1;
    /** 保留顺序队列的固定二百五十六槽容量。 */
    private static final int RETENTION_CAPACITY = 256;
    private final long[] commandIdMost = new long[TABLE_CAPACITY];
    private final long[] commandIdLeast = new long[TABLE_CAPACITY];
    private final StoredResult[] results = new StoredResult[TABLE_CAPACITY];
    private final StoredResult[] recordRing = new StoredResult[TABLE_CAPACITY];
    private final int[] freeRecordSlots = new int[TABLE_CAPACITY];
    private int freeRecordCount;
    /** 按保留顺序排放哈希表槽号的循环队列，负一表示空洞。 */
    private final int[] retentionSlots = new int[RETENTION_CAPACITY];
    private final int[] retentionPositions = new int[TABLE_CAPACITY];
    private final ResponseSlotPool responseSlotPool;
    private int retentionHead;
    private int retentionTail;
    private int size;
    private long commandResultBytes;
    private long nextResultRetentionSequence;

    /** 从已有有序结果建立账本，不接入响应池的引用计数。 */
    public CommandResultLog(LinkedHashMap<UUID, StoredResult> results) {
        this(results, null);
    }

    /** 预建结果记录和空闲槽，检查输入非空及条数后按迭代顺序恢复记录，再统计协议字节和下一保留序号。完整恢复元数据验证由 validateResultLedger 单独承担。 */
    public CommandResultLog(LinkedHashMap<UUID, StoredResult> results, ResponseSlotPool responseSlotPool) {
        this.responseSlotPool = responseSlotPool;
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
        commandResultBytes = commandResultLogBytes(results);
        nextResultRetentionSequence = nextRetentionSequence(results);
    }

    /** 判断当前是否没有保留任何命令结果。 */
    public boolean isEmpty() { return size == 0; }

    /** 按 UUID 两段数值查找当前记录，空账本或未命中返回 null。 */
    public StoredResult get(long most, long least) {
        if (size == 0) return null;
        return results[locate(most, least)];
    }

    /** UUID 为空直接返回 null，否则复用两段数值查找。 */
    public StoredResult get(UUID id) {
        if (id == null) return null;
        return get(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    /** 使用已取得的非空保留记录核对原命令身份。内容冲突返回 IDEMPOTENCY_CONFLICT，匹配则保留原终态和应用计数、把本次响应标记 DUPLICATE，并为池内字节增加独立发送引用。 */
    public CoreResponse duplicateResponse(StoredResult duplicate,
                                   CommandFingerprint fingerprint,
                                   long appliedCommandCount) {
        if (!duplicate.fingerprint.equals(fingerprint)) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                    CoreResultCode.IDEMPOTENCY_CONFLICT, appliedCommandCount, EMPTY_RESPONSE_DATA);
        }
        if (responseSlotPool != null) responseSlotPool.retain(duplicate.responseDataUnsafe());
        return CoreResponse.owned(ResponseStatus.DUPLICATE,
                duplicate.status(), duplicate.resultCode(), duplicate.appliedCommandCount(),
                duplicate.responseDataUnsafe(),
                duplicate.responseDataOffsetUnsafe(), duplicate.responseDataLength());
    }

    /** 仅在快照或导出边界按保留序号物化只读有序 Map。Map 结构独立，但值仍是当前复用记录的借用引用，不能把它当作永久不变的深复制。 */
    public Map<UUID, StoredResult> entries() {
        ArrayList<Integer> slots = new ArrayList<>(size);
        for (int slot = 0; slot < TABLE_CAPACITY; slot++) if (results[slot] != null) slots.add(slot);
        slots.sort(Comparator.comparingLong(slot -> results[slot].retentionSequence()));
        LinkedHashMap<UUID, StoredResult> snapshot = new LinkedHashMap<>(Math.max(4, size * 2));
        for (int slot : slots) snapshot.put(new UUID(commandIdMost[slot], commandIdLeast[slot]), results[slot]);
        return Collections.unmodifiableMap(snapshot);
    }

    /** 逐项累加结果协议固定字段和有效载荷长度，数值溢出直接报错。 */
    static long commandResultLogBytes(Map<UUID, StoredResult> results) {
        long bytes = 0;
        for (Map.Entry<UUID, StoredResult> entry : results.entrySet()) {
            bytes = Math.addExact(bytes, resultEntryBytes(entry.getValue()));
        }
        return bytes;
    }

    /** 计算一条保留结果在快照协议中的固定字段加有效载荷字节数，不按底层数组容量计费。 */
    static long resultEntryBytes(StoredResult result) {
        return Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataLength());
    }

    /** 从现有保留序号求下一序号，空账本从一开始。已有序号加一溢出直接报错。 */
    static long nextRetentionSequence(Map<UUID, StoredResult> results) {
        long next = 1;
        for (StoredResult result : results.values()) {
            next = Math.max(next, Math.incrementExact(result.retentionSequence()));
        }
        return next;
    }

    /** 验证条数、总协议字节及按迭代顺序严格递增的正保留序号，并拒绝空键或空值。 */
    public static void validateResultLedger(Map<UUID, StoredResult> results) {
        if (results == null || results.size() > MAX_IDEMPOTENCY_RESULTS) {
            throw new IllegalArgumentException("invalid result ledger count");
        }
        long bytes = commandResultLogBytes(results);
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

    /** 以整个响应数组作为有效载荷交给切片入口。 */
    public void storeOwnedResult(UUID commandId, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData) {
        java.util.Objects.requireNonNull(commandId, "command id");
        storeOwnedResult(commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                fingerprint, status, resultCode, appliedCommandCount, responseData, 0,
                responseData == null ? 0 : responseData.length);
    }

    public void storeOwnedResult(long commandIdMsb, long commandIdLsb, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData) {
        storeOwnedResult(commandIdMsb, commandIdLsb, fingerprint, status, resultCode, appliedCommandCount,
                responseData, 0,
                responseData == null ? 0 : responseData.length);
    }

    /** 校验响应切片和单项字节上限，借另一预建记录保存结果。 */
    public void storeOwnedResult(long commandIdMsb, long commandIdLsb, CommandFingerprint fingerprint, ResponseStatus status,
                          CoreResultCode resultCode, long appliedCommandCount,
                          byte[] responseData, int responseOffset, int responseLength) {
        if (responseData == null) {
            responseData = TradingCore.EMPTY_RESPONSE_DATA;
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
        int slot = locate(commandIdMsb, commandIdLsb);
        StoredResult previous = results[slot];
        long retentionSequence = previous == null ? nextResultRetentionSequence : previous.retentionSequence();
        byte[] previousResponse = previous == null ? null : previous.responseDataUnsafe();
        long previousBytes = previous == null ? 0 : resultEntryBytes(previous);
        if (freeRecordCount == 0) throw new IllegalStateException("result commit ring is full");
        // 方法返回后，Owner 命令或输出队列仍持有终态响应。
        // 账本另持一份池引用，避免淘汰结果时覆盖尚未完成发送的响应字节。
        if (responseSlotPool != null) {
            if (previous != null && previousResponse == responseData) {
                responseSlotPool.retain(responseData);
            } else {
                responseSlotPool.retainLedger(responseData);
            }
        }
        StoredResult retained = recordRing[freeRecordSlots[--freeRecordCount]];
        retained.set(fingerprint, status, resultCode, appliedCommandCount,
                responseData, responseOffset, responseLength, retentionSequence, true);
        long nextSequence = previous == null ? Math.incrementExact(nextResultRetentionSequence)
                : nextResultRetentionSequence;
        if (previous == null) {
            commandIdMost[slot] = commandIdMsb;
            commandIdLeast[slot] = commandIdLsb;
            enqueueRetentionSlot(slot);
            size++;
        } else if (responseSlotPool != null && previousResponse != responseData) {
            responseSlotPool.releaseLedger(previousResponse);
        }
        if (previous != null) freeRecordSlots[freeRecordCount++] = previous.ringSlot;
        results[slot] = retained;
        nextResultRetentionSequence = nextSequence;
        commandResultBytes = Math.addExact(commandResultBytes - previousBytes, resultBytes);
        while (size > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            int oldest = oldestSlot(commandIdMsb, commandIdLsb);
            if (oldest < 0) throw new IllegalStateException("result ledger protected entry exceeds bound");
            commandResultBytes = Math.subtractExact(commandResultBytes, resultEntryBytes(results[oldest]));
            remove(oldest);
        }
    }

    /** 构造恢复时将已有结果复制进预建记录并接入保留队列。 */
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

    private int locate(long most, long least) {
        int slot = hash(most, least);
        while (results[slot] != null) {
            if (commandIdMost[slot] == most && commandIdLeast[slot] == least) return slot;
            slot = (slot + 1) & TABLE_MASK;
        }
        return slot;
    }

    private int oldestSlot(long protectedMost, long protectedLeast) {
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
        if (responseSlotPool != null && removed != null) {
            responseSlotPool.releaseLedger(removed.responseDataUnsafe());
        }
        int position = retentionPositions[slot];
        retentionSlots[position & (RETENTION_CAPACITY - 1)] = -1;
        retentionPositions[slot] = -1;
        size--;
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

    /** 仅跳过保留队列头已经失效的空洞，不改变剩余结果的相对顺序。 */
    private void advanceRetentionHead() {
        while (retentionHead < retentionTail
                && retentionSlots[retentionHead & (RETENTION_CAPACITY - 1)] < 0) {
            retentionHead++;
        }
    }

    /** 把有效保留槽按原顺序压紧，修正反向位置并缩短尾游标，清掉中间空洞占用的跨度。 */
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

    /** 一条命令的结果描述，记录指纹、原终态、有效响应切片及保留顺序。 */
    public static final class StoredResult {
        private final int ringSlot;
        /** 本记录保存的稳定命令指纹，不随原输入租约归还而改变。 */
        private CommandFingerprint fingerprint;
        private CommandFingerprint fingerprintSlot;
        @Accessors(fluent = true, chain = false)
        @Getter
        private ResponseStatus status;
        /** 原命令保存的确定性结果码。读取原命令的确定性业务结果码。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private CoreResultCode resultCode;
        @Accessors(fluent = true, chain = false)
        @Getter
        private long appliedCommandCount;
        private byte[] responseData;
        private int responseOffset;
        private int responseLength;
        /** 本记录的保留顺序号，替换内容时继续沿用。读取本结果的保留顺序，内容替换不将它挪到队尾。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        private long retentionSequence;
        private StoredResult(int ringSlot) {
            this.ringSlot = ringSlot;
            fingerprint = null;
            status = null;
            resultCode = null;
            responseData = TradingCore.EMPTY_RESPONSE_DATA;
        }

        /** 创建独立结果记录，默认复制响应有效字节，避免调用方改原数组影响结果。 */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, retentionSequence, false);
        }

        /** 以整个响应数组创建独立记录，由 ownedResponseData 决定直接接管数组还是复制有效字节。 */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
                     long appliedCommandCount,
                     byte[] responseData, long retentionSequence, boolean ownedResponseData) {
            this(fingerprint, status, resultCode, appliedCommandCount,
                    responseData, 0, responseData == null ? 0 : responseData.length, retentionSequence,
                    ownedResponseData);
        }

        /** 创建不属于预建池的记录，校验指纹、状态、非负计数及切片范围后保存。 */
        public StoredResult(CommandFingerprint fingerprint, ResponseStatus status, CoreResultCode resultCode,
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

        /** 借用指纹复制到记录自有指纹槽，响应为空则规范为空数组，有所有权则保留切片，否则只复制有效范围。 */
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
            this.fingerprint = fingerprint.isSlotValue()
                    ? (fingerprintSlot = fingerprint.copyIntoSlot(fingerprintSlot)) : fingerprint;
            this.status = status;
            this.resultCode = resultCode;
            this.appliedCommandCount = appliedCommandCount;
            byte[] normalized = responseData == null ? TradingCore.EMPTY_RESPONSE_DATA : responseData;
            if (normalized == TradingCore.EMPTY_RESPONSE_DATA || responseLength == 0) {
                this.responseData = TradingCore.EMPTY_RESPONSE_DATA;
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
        }

        private void copyFrom(StoredResult other) {
            set(other.fingerprint, other.status, other.resultCode, other.appliedCommandCount,
                    other.responseData, other.responseOffset,
                    other.responseLength, other.retentionSequence, true);
        }

        /** 返回指纹的不可变值，避免对外暴露会随记录复用改写的指纹槽。 */
        public CommandFingerprint fingerprint() { return fingerprint.immutableValue(); }

        /** 借出原响应底层数组，必须连同偏移和有效长度使用，不能读写容量尾部或越过借用期。 */
        public byte[] responseDataUnsafe() {
            return responseData;
        }

        /** 读取有效响应在底层数组中的起始偏移。 */
        public int responseDataOffsetUnsafe() { return responseOffset; }
        /** 读取有效响应字节数，不是底层数组容量。 */
        public int responseDataLength() { return responseLength; }

        /** 复制有效响应切片供外部持有。 */
        public byte[] responseData() {
            return responseLength == 0 ? TradingCore.EMPTY_RESPONSE_DATA
                    : java.util.Arrays.copyOfRange(responseData, responseOffset, responseOffset + responseLength);
        }

        /** 比较指纹、状态、计数、保留序号和有效响应字节。 */
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

        /** 按与相等性一致的元数据及有效字节计算哈希，不把底层数组空余容量算进去。 */
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
