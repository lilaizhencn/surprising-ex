package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 命令幂等结果账本：独占保留顺序、字节上限和结果摘要；仅 owner 读写。 */
final class CommandResultLedger {
    /** 命令结果及其保留顺序；不是第二份订单或账户状态。 */
    private final LinkedHashMap<UUID, StoredResult> commandResults;
    /** 当前保留结果的协议字节总量，用于执行内存上限。 */
    private long commandResultBytes;
    /** 下一条新结果的保留序号；更新旧结果保持原序号。 */
    private long nextResultRetentionSequence;

    CommandResultLedger(LinkedHashMap<UUID, StoredResult> results) {
        commandResults = results;
        commandResultBytes = resultLedgerBytes(results);
        nextResultRetentionSequence = nextRetentionSequence(results);
    }

    StoredResult get(UUID id) { return commandResults.get(id); }
    Map<UUID, StoredResult> entries() { return Collections.unmodifiableMap(commandResults); }

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
        long resultBytes = resultEntryBytes(result);
        if (resultBytes > MAX_RESULT_LEDGER_BYTES) {
            throw new IllegalArgumentException("result ledger entry exceeds byte bound");
        }
        StoredResult previous = commandResults.get(commandId);
        if (previous != null) {
            StoredResult retained = result.withRetentionSequence(previous.retentionSequence());
            commandResults.put(commandId, retained);
            commandResultBytes = Math.addExact(Math.subtractExact(commandResultBytes, resultEntryBytes(previous)),
                    resultBytes);
        } else {
            long retentionSequence = nextResultRetentionSequence;
            StoredResult retained = result.withRetentionSequence(retentionSequence);
            nextResultRetentionSequence = Math.incrementExact(nextResultRetentionSequence);
            commandResults.put(commandId, retained);
            commandResultBytes = Math.addExact(commandResultBytes, resultBytes);
        }
        while (commandResults.size() > MAX_IDEMPOTENCY_RESULTS
                || commandResultBytes > MAX_RESULT_LEDGER_BYTES) {
            Iterator<Map.Entry<UUID, StoredResult>> iterator = commandResults.entrySet().iterator();
            Map.Entry<UUID, StoredResult> oldest = null;
            while (iterator.hasNext()) {
                Map.Entry<UUID, StoredResult> candidate = iterator.next();
                if (!candidate.getKey().equals(commandId)) {
                    oldest = candidate;
                    iterator.remove();
                    break;
                }
            }
            if (oldest == null) {
                throw new IllegalStateException("result ledger protected entry exceeds bound");
            }
            commandResultBytes = Math.subtractExact(commandResultBytes, resultEntryBytes(oldest.getValue()));
        }
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
            byte[] normalized = responseData == null ? new byte[0] : responseData;
            this.responseData = ownedResponseData ? normalized : normalized.clone();
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
