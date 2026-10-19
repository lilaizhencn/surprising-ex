package com.surprising.aeron.service.matcher;
import com.surprising.aeron.service.config.TradingCpuAffinity;

import com.surprising.aeron.service.orchestration.CommandSlot;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.orchestration.CommandSlotRing;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Partition-local synchronous matcher workers. A stable instrumentId route selects exactly one SPSC queue;
 * completion lookup uses that route and never waits for another shard's queue head.
 */
public final class MatcherPipelineGroup implements AutoCloseable {
    private final MatcherCommandPipeline[] shards;
    private final CommandSlotRing contexts;
    /** Matcher workers set one bit on publication; the Owner drains only signalled shards. */
    private final com.surprising.aeron.service.state.TradingRuntimeState.PaddedReadyMask completedShardMask;
    private volatile boolean completionSignalConfigured;

    public void completionSignal(Runnable signal) {
        completionSignalConfigured = true;
        for (int shardId = 0; shardId < shards.length; shardId++) {
            final int currentShardId = shardId;
            shards[shardId].completionSignal(() -> {
                if (completedShardMask.setBit(currentShardId) && signal != null) signal.run();
            });
        }
    }

    public MatcherPipelineGroup(int shardCount, int capacityPerShard, boolean startImmediately, CommandSlotRing contexts) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("matcher shard count must be a power of two");
        }
        this.contexts = java.util.Objects.requireNonNull(contexts, "matching sequence contexts");
        completedShardMask = new com.surprising.aeron.service.state.TradingRuntimeState.PaddedReadyMask(shardCount);
        shards = new MatcherCommandPipeline[shardCount];
        for (int shard = 0; shard < shardCount; shard++) {
            shards[shard] = new MatcherCommandPipeline(shard, capacityPerShard, false);
        }
        if (startImmediately) start((IntConsumer) null);
    }

    /** 各撮合分片与账户状态由当前交易线程一起拥有。 */
    public void startInline(IntConsumer shardActivation) {
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            shards[shardId].startInline(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId));
        }
    }

    public void start(IntConsumer shardActivation) {
        start(shardActivation, TradingCpuAffinity.disabled());
    }

    public void start(IntConsumer shardActivation,
            TradingCpuAffinity affinity) {
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            shards[shardId].start(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId), affinity);
        }
    }

    public void submit(int shardId, long coreSequence, Supplier<?> command) {
        submit(shardId, coreSequence, command, null);
    }

    public void submit(int shardId, long coreSequence, Supplier<?> command,
                com.surprising.aeron.service.state.MatcherSettlementEvent settlement) {
        if (coreSequence <= 0 || command == null) throw new IllegalArgumentException("invalid matcher submission");
        MatcherCommandPipeline shard = shard(shardId);
        CommandSlot context = contexts.required(coreSequence);
        context.claimMatcherSubmission(shardId);
        try {
            if (settlement != null && settlement.direct()) {
                settlement.matcherCompletionRoute(context, shardId);
                shard.submit(coreSequence, command, settlement);
            }
            else shard.submit(coreSequence, command, settlement, context);
        } catch (RuntimeException failure) {
            if (context.submittedMatcherShard() == shardId) context.releaseMatcherSubmission(shardId);
            throw failure;
        }
    }

    public CoreMatchingResult poll(long coreSequence) {
        if (coreSequence <= 0 || !contexts.claimed(coreSequence)) return null;
        drainMatchingCompletions();
        return contexts.required(coreSequence).takeMatchingCompletion();
    }

    /** Read-only completion probe; control results belong to their caller. */
    public boolean hasMatchingCompletions() {
        if (completedShardMask.get() != 0) return true;
        if (!completionSignalConfigured) {
            for (MatcherCommandPipeline shard : shards) {
                if (shard.completedMatchingSequence() != 0) return true;
            }
        }
        return false;
    }

    /** Retire transport cells only. Matcher has already published each command's result. */
    public void drainMatchingCompletions() {
        long readyMask = completedShardMask.getAndClear();
        if (!completionSignalConfigured) {
            for (int shardId = 0; shardId < shards.length; shardId++) {
                if (shards[shardId].completedMatchingSequence() != 0) readyMask |= 1L << shardId;
            }
        }
        for (int shardId = 0; shardId < shards.length; shardId++) {
            if ((readyMask & (1L << shardId)) == 0) continue;
            MatcherCommandPipeline shard = shards[shardId];
            long sequence;
            while ((sequence = shard.completedMatchingSequence()) != 0) {
                // Also propagates worker failures. No access to an already-recycled CommandSlot.
                if (shard.poll(sequence) == null) break;
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
