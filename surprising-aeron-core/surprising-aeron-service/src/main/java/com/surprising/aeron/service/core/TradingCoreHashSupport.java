package com.surprising.aeron.service.core;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes;
import java.util.Map;

/** 确定性业务状态、费用政策、待完成划转与来源序号 FNV-1a 摘要计算工具。 */
final class TradingCoreHashSupport {
    private static final long HASH_OFFSET_BASIS = TradingCore.ExecutionState.HASH_OFFSET_BASIS;
    private static final long HASH_PRIME = TradingCore.ExecutionState.HASH_PRIME;

    private TradingCoreHashSupport() {
    }

    static long canonicalBusinessStateHash(
            long base,
            Map<Long, CoreFeePolicyState> policies,
            Map<Long, TransferFundsCommand> transfers) {
        long feePolicyHash = computeFeePolicyHash(policies);
        if (feePolicyHash != 0) base = mix(base, feePolicyHash);
        long transferHash = computeTransferHash(transfers);
        return transferHash == 0 ? base : mix(base, transferHash);
    }

    static long computeTransferHash(Map<Long, TransferFundsCommand> transfers) {
        if (transfers.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var command : transfers.values()) {
            digest = mix(digest, command.transferId());
            digest = mix(digest, command.sourceUserId());
            digest = mix(digest, command.targetUserId());
            digest = mix(digest, SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = mix(digest, SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = mixText(digest, command.sourceAccountType());
            digest = mixText(digest, command.targetAccountType());
            digest = mixText(digest, command.asset());
            digest = mix(digest, command.amountUnits());
            digest = mixText(digest, command.referenceId());
            digest = mixText(digest, command.reason());
        }
        return digest;
    }

    static long computeFeePolicyHash(Map<Long, CoreFeePolicyState> policies) {
        if (policies.isEmpty()) return 0;
        long digest = HASH_OFFSET_BASIS;
        for (var policy : policies.values()) {
            digest = mix(digest, policy.policyId());
            digest = mix(digest, policy.policyRevision());
            digest = mix(digest, policy.userId());
            digest = mixText(digest, policy.instrumentId());
            digest = mix(digest, policy.makerFeeRatePpm());
            digest = mix(digest, policy.takerFeeRatePpm());
            digest = mix(digest, policy.sourcePriority());
            digest = mix(digest, policy.active() ? 1 : 0);
            digest = mix(digest, policy.effectiveFromEpochMillis());
            digest = mix(digest, policy.expireAtEpochMillis());
        }
        return digest;
    }

    static long mixText(long hash, String value) {
        if (value == null || value.isEmpty()) return mix(hash, 0);
        int length = value.length();
        boolean isAscii = true;
        for (int i = 0; i < length; i++) {
            if (value.charAt(i) >= 0x80) {
                isAscii = false;
                break;
            }
        }
        if (isAscii) {
            long result = mix(hash, length);
            for (int i = 0; i < length; i++) {
                result ^= value.charAt(i);
                result *= HASH_PRIME;
            }
            return result;
        }
        int utf8Len = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) utf8Len++;
            else if (c < 0x800) utf8Len += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                utf8Len += 4;
                i++;
            } else if (!Character.isSurrogate(c)) utf8Len += 3;
            else utf8Len += 1;
        }
        long result = mix(hash, utf8Len);
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                result ^= c;
                result *= HASH_PRIME;
            } else if (c < 0x800) {
                result ^= (0xc0 | (c >>> 6));
                result *= HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= HASH_PRIME;
            } else if (!Character.isSurrogate(c)) {
                result ^= (0xe0 | (c >>> 12));
                result *= HASH_PRIME;
                result ^= (0x80 | ((c >>> 6) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | (c & 0x3f));
                result *= HASH_PRIME;
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                int point = Character.toCodePoint(c, value.charAt(++i));
                result ^= (0xf0 | (point >>> 18));
                result *= HASH_PRIME;
                result ^= (0x80 | ((point >>> 12) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | ((point >>> 6) & 0x3f));
                result *= HASH_PRIME;
                result ^= (0x80 | (point & 0x3f));
                result *= HASH_PRIME;
            } else {
                result ^= '?';
                result *= HASH_PRIME;
            }
        }
        return result;
    }

    static long mix(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= HASH_PRIME;
        }
        return result;
    }

    static long sourceSequenceDigest(Map<TradingCore.SourceKey, Long> sequences) {
        long digest = 0;
        for (Map.Entry<TradingCore.SourceKey, Long> entry : sequences.entrySet()) {
            digest ^= sourceSequenceDigest(entry.getKey(), entry.getValue());
        }
        return digest;
    }

    static long sourceSequenceDigest(TradingCore.SourceKey key, long sequence) {
        long digest = HASH_OFFSET_BASIS;
        digest = mix(digest, key.source().wireCode());
        digest = mix(digest, key.sourceId());
        return mix(digest, sequence);
    }
}
