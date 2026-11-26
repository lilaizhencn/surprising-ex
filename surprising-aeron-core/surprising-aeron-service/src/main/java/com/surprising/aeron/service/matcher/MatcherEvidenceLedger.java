package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.service.matcher.OrderBookSnapshot.ShardProgress;
import com.surprising.aeron.service.settlement.TradeSettlement;
import exchange.core2.core.common.MatcherResult;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 撮合分片序号与原生证据账本：单所有者推进，acquire/release 供快照栅栏读取。 */
final class MatcherEvidenceLedger {

    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private final LaneTopology topology;
    private final int shardCount;
    private final long[] issuedSequences;
    private final long[] shardSequences;
    private final long[] shardNativeSequences;

    MatcherEvidenceLedger(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("matcher topology is required");
        this.topology = topology;
        this.shardCount = topology.matchingEngineCount() + 1;
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
    }

    MatcherEvidenceLedger(LaneTopology topology, long sequenceFloor, List<ShardProgress> progress) {
        if (topology == null || sequenceFloor < 0 || progress == null
                || progress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("invalid matcher evidence restore");
        }
        this.topology = topology;
        this.shardCount = progress.size();
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
        boolean[] restored = new boolean[progress.size()];
        for (ShardProgress shard : progress) {
            int index = index(shard.matcherShardId());
            if (restored[index]) throw new IllegalArgumentException("duplicate matcher shard progress");
            restored[index] = true;
            int offset = offset(index);
            shardSequences[offset] = shard.matcherSequence();
            issuedSequences[offset] = Math.max(sequenceFloor, shard.matcherSequence());
        }
        for (boolean present : restored) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
    }

    long nextSequence(int matcherShardId) {
        int offset = offset(index(matcherShardId));
        long next = Math.incrementExact((long) LONGS.getAcquire(issuedSequences, offset));
        LONGS.setRelease(issuedSequences, offset, next);
        return next;
    }

    void recordControlNative(int shardId, long nativeSequence) {
        advanceStrictly(shardNativeSequences, index(shardId), nativeSequence,
                "matcher shard native sequence is not strictly increasing");
    }

    OrderMatchingResult.Control bindControl(long coreSequence, UUID commandId,
                                            long sequence, OrderMatchingResult.Control result) {
        if (commandId == null || result == null) throw new IllegalArgumentException("matcher evidence is required");
        advanceStrictly(shardSequences, index(-1), sequence,
                "matcher shard sequence is not strictly increasing");
        return result.bindEvidenceInPlace(coreSequence, commandId.getMostSignificantBits(),
                commandId.getLeastSignificantBits(), sequence);
    }

    MatcherResult publishNative(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            MatcherResult result, TradeSettlement target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher publication is required");
        int matcherIndex = index(matcherShardId);
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, matcherIndex, sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeResult(result, coreSequence,
                commandIdMsb, commandIdLsb, orderId, sequence, matcherShardId);
        return result;
    }

    MatcherResult publishReplacement(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            MatcherResult cancellation, MatcherResult result, TradeSettlement target) {
        if (cancellation == null || result == null || target == null)
            throw new IllegalArgumentException("native matcher replacement publication is required");
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeReplacement(cancellation, result, coreSequence,
                commandIdMsb, commandIdLsb, orderId, sequence, matcherShardId);
        return result;
    }

    MatcherResult publishBatchItem(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, int nativeMatcherShardId,
            MatcherResult result, int index, TradeSettlement target) {
        if (result == null || target == null)
            throw new IllegalArgumentException("native matcher batch publication is required");
        long nativeSequence = result.sequence();
        if (nativeSequence > 0) {
            advanceStrictly(shardNativeSequences, index(nativeMatcherShardId), nativeSequence,
                    "matcher shard native sequence is not strictly increasing");
        }
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectNativeBatchItem(result, index, coreSequence,
                commandIdMsb, commandIdLsb, orderId, sequence, matcherShardId);
        return result;
    }

    void publishRejection(
            long coreSequence, long commandIdMsb, long commandIdLsb, long orderId,
            long sequence, int matcherShardId, String resultCode, TradeSettlement target) {
        if (resultCode == null || resultCode.isBlank() || target == null)
            throw new IllegalArgumentException("matcher rejection publication is required");
        advanceStrictly(shardSequences, index(matcherShardId), sequence,
                "matcher shard sequence is not strictly increasing");
        target.publishDirectRejection(resultCode, coreSequence,
                commandIdMsb, commandIdLsb, orderId, sequence, matcherShardId);
    }

    List<ShardProgress> snapshot() {
        ArrayList<ShardProgress> progress = new ArrayList<>(shardCount);
        for (int index = 0; index < shardCount; index++) {
            int offset = offset(index);
            progress.add(new ShardProgress(index - 1,
                    (long) LONGS.getAcquire(shardSequences, offset)));
        }
        return List.copyOf(progress);
    }

    private int index(int matcherShardId) {
        if (matcherShardId < -1 || matcherShardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return matcherShardId + 1;
    }

    private static void advanceStrictly(long[] values, int index, long next, String message) {
        int offset = offset(index);
        long previous = (long) LONGS.getAcquire(values, offset);
        if (next <= previous) {
            throw new IllegalStateException(message + " shard=" + (index - 1)
                    + " previous=" + previous + " next=" + next);
        }
        LONGS.setRelease(values, offset, next);
    }

    private static int offset(int index) {
        return index * CACHE_LINE_LONGS;
    }
}
