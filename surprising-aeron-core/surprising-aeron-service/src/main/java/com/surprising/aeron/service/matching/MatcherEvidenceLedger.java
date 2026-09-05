package com.surprising.aeron.service.matching;

import com.surprising.aeron.service.state.LaneTopology;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

final class MatcherEvidenceLedger {

    private static final int CACHE_LINE_LONGS = 16;
    private static final VarHandle LONGS = MethodHandles.arrayElementVarHandle(long[].class);

    private final LaneTopology topology;
    private final int shardCount;
    private final long[] issuedSequences;
    private final long[] shardSequences;
    private final long[] shardPrefixes;
    private final long[] shardNativeSequences;

    MatcherEvidenceLedger(LaneTopology topology) {
        this(topology, 0, initialProgress(topology));
    }

    MatcherEvidenceLedger(LaneTopology topology, long sequenceFloor, List<MatcherShardProgress> progress) {
        if (topology == null || sequenceFloor < 0 || progress == null
                || progress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("invalid matcher evidence restore");
        }
        this.topology = topology;
        this.shardCount = progress.size();
        int paddedLength = Math.multiplyExact(shardCount, CACHE_LINE_LONGS);
        this.shardSequences = new long[paddedLength];
        this.shardPrefixes = new long[paddedLength];
        this.shardNativeSequences = new long[paddedLength];
        this.issuedSequences = new long[paddedLength];
        boolean[] restored = new boolean[progress.size()];
        for (MatcherShardProgress shard : progress) {
            int index = index(shard.matcherShardId());
            if (restored[index]) throw new IllegalArgumentException("duplicate matcher shard progress");
            restored[index] = true;
            int offset = offset(index);
            shardSequences[offset] = shard.matcherSequence();
            issuedSequences[offset] = Math.max(sequenceFloor, shard.matcherSequence());
            shardPrefixes[offset] = shard.prefixDigest();
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

    CoreMatchingResult bind(
            long coreSequence,
            java.util.UUID commandId,
            long orderId,
            long instrumentVersion,
            long aeronTimestamp,
            long sequence,
            int matcherShardId,
            CoreMatchingResult result) {
        int index = index(matcherShardId);
        long nativeSequence = result.nativeCommand().nativeSequence();
        if (nativeSequence > 0) advanceStrictly(shardNativeSequences, index, nativeSequence,
                "matcher shard native sequence is not strictly increasing shard=" + matcherShardId);
        advanceStrictly(shardSequences, index, sequence,
                "matcher shard sequence is not strictly increasing");
        CoreMatchingResult.NativeCommand nativeCommand = new CoreMatchingResult.NativeCommand(
                coreSequence, commandId.getMostSignificantBits(), commandId.getLeastSignificantBits(),
                orderId, instrumentVersion,
                nativeSequence, sequence, aeronTimestamp, matcherShardId);
        int offset = offset(index);
        long before = (long) LONGS.getAcquire(shardPrefixes, offset);
        long after = MatcherPrefixDigest.next(before, nativeCommand, result);
        LONGS.setRelease(shardPrefixes, offset, after);
        return result.withEvidence(nativeCommand, new CoreMatchingResult.MatcherPrefix(before, after));
    }

    List<MatcherShardProgress> snapshot() {
        ArrayList<MatcherShardProgress> progress = new ArrayList<>(shardCount);
        for (int index = 0; index < shardCount; index++) {
            int offset = offset(index);
            progress.add(new MatcherShardProgress(index - 1,
                    (long) LONGS.getAcquire(shardSequences, offset),
                    (long) LONGS.getAcquire(shardPrefixes, offset)));
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
            throw new IllegalStateException(message + " previous=" + previous + " next=" + next);
        }
        // A matching shard has one owner thread. Release publication is sufficient for the
        // snapshot fence and avoids a locked compare-and-set on every matched command.
        LONGS.setRelease(values, offset, next);
    }

    private static int offset(int index) {
        return index * CACHE_LINE_LONGS;
    }

    private static List<MatcherShardProgress> initialProgress(LaneTopology topology) {
        if (topology == null) throw new IllegalArgumentException("matcher topology is required");
        ArrayList<MatcherShardProgress> progress = new ArrayList<>(topology.matchingEngineCount() + 1);
        for (int shardId = -1; shardId < topology.matchingEngineCount(); shardId++) {
            progress.add(new MatcherShardProgress(
                    shardId, 0, CoreMatchingResult.MatcherPrefix.initialDigest()));
        }
        return List.copyOf(progress);
    }
}
