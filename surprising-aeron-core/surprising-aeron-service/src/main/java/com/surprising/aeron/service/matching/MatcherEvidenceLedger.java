package com.surprising.aeron.service.matching;

import com.surprising.aeron.service.state.LaneTopology;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

final class MatcherEvidenceLedger {

    private final LaneTopology topology;
    private final AtomicLong matcherSequence;
    private final AtomicLongArray shardSequences;
    private final AtomicLongArray shardPrefixes;
    private final AtomicLongArray shardNativeSequences;

    MatcherEvidenceLedger(LaneTopology topology) {
        this(topology, 0, initialProgress(topology));
    }

    MatcherEvidenceLedger(LaneTopology topology, long sequenceFloor, List<MatcherShardProgress> progress) {
        if (topology == null || sequenceFloor < 0 || progress == null
                || progress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("invalid matcher evidence restore");
        }
        this.topology = topology;
        this.shardSequences = new AtomicLongArray(progress.size());
        this.shardPrefixes = new AtomicLongArray(progress.size());
        this.shardNativeSequences = new AtomicLongArray(progress.size());
        long maximumSequence = sequenceFloor;
        boolean[] restored = new boolean[progress.size()];
        for (MatcherShardProgress shard : progress) {
            int index = index(shard.matcherShardId());
            if (restored[index]) throw new IllegalArgumentException("duplicate matcher shard progress");
            restored[index] = true;
            shardSequences.set(index, shard.matcherSequence());
            shardPrefixes.set(index, shard.prefixDigest());
            maximumSequence = Math.max(maximumSequence, shard.matcherSequence());
        }
        for (boolean present : restored) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        this.matcherSequence = new AtomicLong(maximumSequence);
    }

    long nextSequence() {
        return matcherSequence.incrementAndGet();
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
                "matcher shard native sequence is not strictly increasing");
        advanceStrictly(shardSequences, index, sequence,
                "matcher shard sequence is not strictly increasing");
        CoreMatchingResult.NativeCommand nativeCommand = new CoreMatchingResult.NativeCommand(
                coreSequence, commandId.toString(), orderId, instrumentVersion,
                nativeSequence, sequence, aeronTimestamp, matcherShardId);
        long before = shardPrefixes.get(index);
        long after = MatcherPrefixDigest.next(before, nativeCommand, result);
        if (!shardPrefixes.compareAndSet(index, before, after)) {
            throw new IllegalStateException("matcher shard prefix advanced outside its single-writer order");
        }
        return result.withEvidence(nativeCommand, new CoreMatchingResult.MatcherPrefix(before, after));
    }

    List<MatcherShardProgress> snapshot() {
        ArrayList<MatcherShardProgress> progress = new ArrayList<>(shardSequences.length());
        for (int index = 0; index < shardSequences.length(); index++) {
            progress.add(new MatcherShardProgress(index - 1, shardSequences.get(index), shardPrefixes.get(index)));
        }
        return List.copyOf(progress);
    }

    private int index(int matcherShardId) {
        if (matcherShardId < -1 || matcherShardId >= topology.matchingEngineCount()) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return matcherShardId + 1;
    }

    private static void advanceStrictly(AtomicLongArray values, int index, long next, String message) {
        long previous = values.get(index);
        if (next <= previous || !values.compareAndSet(index, previous, next)) {
            throw new IllegalStateException(message);
        }
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
