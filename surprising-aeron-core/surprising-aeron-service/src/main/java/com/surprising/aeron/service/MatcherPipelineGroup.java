package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;

/**
 * Partition-local synchronous matcher workers. A stable symbol route selects exactly one SPSC queue;
 * completion lookup uses that route and never waits for another shard's queue head.
 */
final class MatcherPipelineGroup implements AutoCloseable {
    @FunctionalInterface
    interface MatchingCompletionConsumer {
        void accept(long coreSequence, CoreMatchingResult result);
    }

    private final MatcherCommandPipeline[] shards;
    private final LongIntHashMap shardByToken;

    MatcherPipelineGroup(int shardCount, int capacityPerShard, boolean startImmediately) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("matcher shard count must be a power of two");
        }
        shards = new MatcherCommandPipeline[shardCount];
        for (int shard = 0; shard < shardCount; shard++) {
            shards[shard] = new MatcherCommandPipeline(shard, capacityPerShard, false);
        }
        shardByToken = new LongIntHashMap(Math.multiplyExact(shardCount, capacityPerShard));
        if (startImmediately) start((IntConsumer) null);
    }

    void start(IntConsumer shardActivation) {
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            shards[shardId].start(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId));
        }
    }

    void submit(int shardId, long coreSequence, Supplier<CoreMatchingResult> command) {
        MatcherCommandPipeline shard = shard(shardId);
        if (shardByToken.containsKey(coreSequence)) {
            throw new IllegalStateException("matcher command token is already routed");
        }
        shard.submit(coreSequence, command);
        shardByToken.put(coreSequence, shardId + 1);
    }

    CoreMatchingResult poll(long coreSequence) {
        int encodedShard = shardByToken.get(coreSequence);
        if (encodedShard == 0) return null;
        CoreMatchingResult result = shards[encodedShard - 1].poll(coreSequence);
        if (result != null) shardByToken.removeKey(coreSequence);
        return result;
    }

    /**
     * Drains only completed matching heads. Control tokens remain at the shard head for their
     * synchronous caller, so this operation is safe to run from the Core owner loop.
     */
    void drainMatchingCompletions(MatchingCompletionConsumer consumer) {
        if (consumer == null) throw new IllegalArgumentException("matching completion consumer is required");
        for (int shardId = 0; shardId < shards.length; shardId++) {
            MatcherCommandPipeline shard = shards[shardId];
            while (true) {
                long coreSequence = shard.completedMatchingSequence();
                if (coreSequence == 0) break;
                int encodedShard = shardByToken.get(coreSequence);
                if (encodedShard != shardId + 1) {
                    throw new IllegalStateException("completed matcher token is not routed to its shard");
                }
                CoreMatchingResult result = shard.poll(coreSequence);
                if (result == null) break;
                shardByToken.removeKey(coreSequence);
                consumer.accept(coreSequence, result);
            }
        }
    }

    CoreMatchingResult await(long coreSequence, long timeoutNanos) {
        int encodedShard = shardByToken.get(coreSequence);
        if (encodedShard == 0) return null;
        CoreMatchingResult result = shards[encodedShard - 1].await(coreSequence, timeoutNanos);
        if (result != null) shardByToken.removeKey(coreSequence);
        return result;
    }

    <T> T call(int shardId, Supplier<T> command, long timeoutNanos) {
        return shard(shardId).call(command, timeoutNanos);
    }

    <T> T call(Supplier<T> command, long timeoutNanos) {
        return call(0, command, timeoutNanos);
    }

    <T> java.util.List<T> callEach(IntFunction<T> command, long timeoutNanos) {
        if (command == null) throw new IllegalArgumentException("matcher shard control is required");
        java.util.ArrayList<T> results = new java.util.ArrayList<>(shards.length);
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            results.add(call(shardId, () -> command.apply(currentShardId), timeoutNanos));
        }
        return java.util.List.copyOf(results);
    }

    int submissionDepth() {
        int depth = 0;
        for (MatcherCommandPipeline shard : shards) depth = Math.addExact(depth, shard.submissionDepth());
        return depth;
    }

    int completionDepth() {
        int depth = 0;
        for (MatcherCommandPipeline shard : shards) depth = Math.addExact(depth, shard.completionDepth());
        return depth;
    }

    int capacity() { return Math.multiplyExact(shards.length, shards[0].capacity()); }

    int submissionHighWaterMark() {
        int maximum = 0;
        for (MatcherCommandPipeline shard : shards) maximum = Math.max(maximum, shard.submissionHighWaterMark());
        return maximum;
    }

    int completionHighWaterMark() {
        int maximum = 0;
        for (MatcherCommandPipeline shard : shards) maximum = Math.max(maximum, shard.completionHighWaterMark());
        return maximum;
    }

    int shardCount() { return shards.length; }

    private MatcherCommandPipeline shard(int shardId) {
        if (shardId < 0 || shardId >= shards.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return shards[shardId];
    }

    @Override
    public void close() { close(null); }

    void closeShards(IntConsumer action) {
        RuntimeException failure = null;
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            try {
                shards[shardId].close(action == null ? null : () -> action.accept(currentShardId));
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    void close(Runnable action) {
        RuntimeException failure = null;
        for (MatcherCommandPipeline shard : shards) {
            try {
                shard.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (action != null) {
            try {
                action.run();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
