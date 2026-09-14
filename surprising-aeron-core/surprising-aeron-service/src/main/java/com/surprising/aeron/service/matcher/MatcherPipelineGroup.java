package com.surprising.aeron.service.matcher;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.orchestration.LaneCommandContextRing;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Partition-local synchronous matcher workers. A stable symbol route selects exactly one SPSC queue;
 * completion lookup uses that route and never waits for another shard's queue head.
 */
public final class MatcherPipelineGroup implements AutoCloseable {
    @FunctionalInterface
    public interface MatchingCompletionConsumer {
        void accept(long coreSequence, CoreMatchingResult result);
    }

    private final MatcherCommandPipeline[] shards;
    private final LaneCommandContextRing contexts;

    public void completionSignal(Runnable signal) {
        for (MatcherCommandPipeline shard : shards) shard.completionSignal(signal);
    }

    public MatcherPipelineGroup(int shardCount, int capacityPerShard, boolean startImmediately, LaneCommandContextRing contexts) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("matcher shard count must be a power of two");
        }
        this.contexts = java.util.Objects.requireNonNull(contexts, "matching sequence contexts");
        shards = new MatcherCommandPipeline[shardCount];
        for (int shard = 0; shard < shardCount; shard++) {
            shards[shard] = new MatcherCommandPipeline(shard, capacityPerShard, false);
        }
        if (startImmediately) start((IntConsumer) null);
    }

    public void start(IntConsumer shardActivation) {
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            shards[shardId].start(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId));
        }
    }

    public void submit(int shardId, long coreSequence, Supplier<CoreMatchingResult> command) {
        submit(shardId, coreSequence, command, null);
    }

    public void submit(int shardId, long coreSequence, Supplier<CoreMatchingResult> command,
                com.surprising.aeron.service.state.MatcherSettlementEvent settlement) {
        if (coreSequence <= 0 || command == null) throw new IllegalArgumentException("invalid matcher submission");
        MatcherCommandPipeline shard = shard(shardId);
        LaneCommandContextRing.Context context = contexts.required(coreSequence);
        context.claimMatcherSubmission(shardId);
        try {
            if (settlement != null && settlement.direct()) {
                settlement.matcherCompletionRelease(() -> context.releaseMatcherSubmission(shardId));
            }
            shard.submit(coreSequence, command, settlement);
        } catch (RuntimeException failure) {
            if (context.submittedMatcherShard() == shardId) context.releaseMatcherSubmission(shardId);
            throw failure;
        }
    }

    public CoreMatchingResult poll(long coreSequence) {
        if (coreSequence <= 0 || !contexts.claimed(coreSequence)) return null;
        LaneCommandContextRing.Context context = contexts.required(coreSequence);
        int shardId = context.submittedMatcherShard();
        if (shardId == -1) return null;
        CoreMatchingResult result = shards[shardId].poll(coreSequence);
        if (result != null) context.releaseMatcherSubmission(shardId);
        return result;
    }

    /** Read-only completion probe; control results belong to their caller. */
    public boolean hasMatchingCompletions() {
        for (MatcherCommandPipeline shard : shards) {
            if (shard.completedMatchingSequence() != 0) return true;
        }
        return false;
    }

    /** Drains matching heads only; control tokens remain for their caller. */
    public void drainMatchingCompletions(MatchingCompletionConsumer consumer) {
        if (consumer == null) throw new IllegalArgumentException("matching completion consumer is required");
        for (int shardId = 0; shardId < shards.length; shardId++) {
            MatcherCommandPipeline shard = shards[shardId];
            while (true) {
                long coreSequence = shard.completedMatchingSequence();
                if (coreSequence == 0) break;
                CoreMatchingResult result = shard.poll(coreSequence);
                if (result == null) break;
                // The existing sequence slot owns the route; no second token index is maintained.
                if (contexts.required(coreSequence).submittedMatcherShard() == shardId)
                    contexts.required(coreSequence).releaseMatcherSubmission(shardId);
                consumer.accept(coreSequence, result);
            }
        }
    }

    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(int shardId,Supplier<T> read) {
        return shard(shardId).readAtSubmissionFence(read);
    }

    public long submitControl(int shardId, Supplier<?> command) { return shard(shardId).submitControl(command); }
    public Object pollControl(int shardId, long token) { return shard(shardId).pollControl(token); }

    /** 查询边界并行读取各分片，仅在所有 Future 完成后聚合；join 不等待未完成任务。 */
    public <T> java.util.concurrent.CompletableFuture<java.util.List<T>> readEachAsync(IntFunction<T> read) {
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<T>>(shards.length);
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int current = shardId;
            futures.add(readAtSubmissionFence(shardId, () -> read.apply(current)));
        }
        return java.util.concurrent.CompletableFuture.allOf(
                futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(java.util.concurrent.CompletableFuture::join).toList());
    }

    public <T> T call(int shardId, Supplier<T> command, long timeoutNanos) {
        return shard(shardId).call(command, timeoutNanos);
    }

    public <T> T call(Supplier<T> command, long timeoutNanos) {
        return call(0, command, timeoutNanos);
    }

    public <T> java.util.List<T> callEach(IntFunction<T> command, long timeoutNanos) {
        if (command == null) throw new IllegalArgumentException("matcher shard control is required");
        java.util.ArrayList<T> results = new java.util.ArrayList<>(shards.length);
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            results.add(call(shardId, () -> command.apply(currentShardId), timeoutNanos));
        }
        return java.util.List.copyOf(results);
    }

    public int submissionDepth() {
        int depth = 0;
        for (MatcherCommandPipeline shard : shards) depth = Math.addExact(depth, shard.submissionDepth());
        return depth;
    }

    public int completionDepth() {
        int depth = 0;
        for (MatcherCommandPipeline shard : shards) depth = Math.addExact(depth, shard.completionDepth());
        return depth;
    }

    public int capacity() { return Math.multiplyExact(shards.length, shards[0].capacity()); }

    public int submissionHighWaterMark() {
        int maximum = 0;
        for (MatcherCommandPipeline shard : shards) maximum = Math.max(maximum, shard.submissionHighWaterMark());
        return maximum;
    }

    public int completionHighWaterMark() {
        int maximum = 0;
        for (MatcherCommandPipeline shard : shards) maximum = Math.max(maximum, shard.completionHighWaterMark());
        return maximum;
    }

    public int shardCount() { return shards.length; }

    private MatcherCommandPipeline shard(int shardId) {
        if (shardId < 0 || shardId >= shards.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return shards[shardId];
    }

    @Override
    public void close() { close(null); }

    public void closeShards(IntConsumer action) {
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

    public void close(Runnable action) {
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
