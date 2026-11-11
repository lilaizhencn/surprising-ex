package com.surprising.aeron.service.matcher;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.support.concurrent.MatcherThread;
import com.surprising.aeron.config.TradingCpuAffinity;
import com.surprising.aeron.service.order.OrderCommand;
import com.surprising.aeron.service.order.OrderCommandQueue.Pool;
import com.surprising.aeron.service.settlement.TradeSettlement;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

/** 按全局分片号路由与执行订单簿命令。分片间可以独立完成，Owner 用就绪位图回收传输槽，单分片内仍保持顺序。 */
public final class OrderBookRouter implements AutoCloseable {
    private final ShardPipeline[] shards;
    /** Owner 现有命令槽环，Matcher 完成结果直接发布到其中，组内不另存一份业务结果表。 */
    private final OrderCommandQueue.Pool contexts;
    /** Matcher 发布完成时置位、Owner 一次取清的就绪位图，用来筛选需要回收的分片。 */
    private final com.surprising.aeron.service.core.PaddedReadyMask completedShardMask;

    public OrderBookRouter(int shardCount, int capacityPerShard, OrderCommandQueue.Pool contexts) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("matcher shard count must be a power of two");
        }
        this.contexts = java.util.Objects.requireNonNull(contexts, "matching sequence contexts");
        completedShardMask = new com.surprising.aeron.service.core.PaddedReadyMask(shardCount);
        shards = new ShardPipeline[shardCount];
        for (int shard = 0; shard < shardCount; shard++) {
            int shardId = shard;
            shards[shard] = new ShardPipeline(shard, capacityPerShard,
                    () -> completedShardMask.setBit(shardId));
        }
    }

    public void start(IntConsumer shardActivation, TradingCpuAffinity affinity) {
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            shards[shardId].start(shardActivation == null ? null
                    : () -> shardActivation.accept(currentShardId), affinity);
        }
    }

    public void submit(int shardId, long coreSequence, Supplier<?> command,
                       TradeSettlement settlement) {
        if (coreSequence <= 0 || command == null) throw new IllegalArgumentException("invalid matcher submission");
        ShardPipeline shard = shard(shardId);
        OrderCommand context = contexts.required(coreSequence);
        context.claimMatcherSubmission(shardId);
        try {
            if (settlement != null && settlement.direct()) {
                settlement.matcherCompletionRoute(context, shardId);
                shard.submit(coreSequence, command, settlement);
            } else {
                shard.submit(coreSequence, command, settlement, context);
            }
        } catch (RuntimeException failure) {
            if (context.submittedMatcherShard() == shardId) context.releaseMatcherSubmission(shardId);
            throw failure;
        }
    }

    public boolean hasMatchingCompletions() {
        return completedShardMask.get() != 0;
    }

    public void drainMatchingCompletions() {
        long readyMask = completedShardMask.getAndClear();
        for (int shardId = 0; shardId < shards.length; shardId++) {
            if ((readyMask & (1L << shardId)) == 0) continue;
            ShardPipeline shard = shards[shardId];
            long sequence;
            while ((sequence = shard.completedMatchingSequence()) != 0) {
                if (shard.poll(sequence) == null) break;
            }
        }
    }

    public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(int shardId, Supplier<T> read) {
        return shard(shardId).readAtSubmissionFence(read);
    }

    public long submitControl(int shardId, Supplier<?> command) { return shard(shardId).submitControl(command); }

    public Object pollControl(int shardId, long token) { return shard(shardId).pollControl(token); }

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

    public int submissionDepth() {
        int depth = 0;
        for (ShardPipeline shard : shards) depth = Math.addExact(depth, shard.submissionDepth());
        return depth;
    }

    public int completionDepth() {
        int depth = 0;
        for (ShardPipeline shard : shards) depth = Math.addExact(depth, shard.completionDepth());
        return depth;
    }

    public int capacity() { return Math.multiplyExact(shards.length, shards[0].capacity()); }

    public int submissionHighWaterMark() {
        int maximum = 0;
        for (ShardPipeline shard : shards) maximum = Math.max(maximum, shard.submissionHighWaterMark());
        return maximum;
    }

    public int completionHighWaterMark() {
        int maximum = 0;
        for (ShardPipeline shard : shards) maximum = Math.max(maximum, shard.completionHighWaterMark());
        return maximum;
    }

    private ShardPipeline shard(int shardId) {
        if (shardId < 0 || shardId >= shards.length) {
            throw new IllegalArgumentException("matcher shard is outside configured topology");
        }
        return shards[shardId];
    }

    public boolean workersStopped() {
        for (ShardPipeline shard : shards) if (!shard.workerStopped()) return false;
        return true;
    }

    @Override
    public void close() { closeShards(null); }

    public void closeShards(IntConsumer action) {
        Throwable failure = null;
        for (int shardId = 0; shardId < shards.length; shardId++) {
            int currentShardId = shardId;
            try {
                shards[shardId].close(action == null ? null : () -> action.accept(currentShardId));
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) failure = closeFailure;
                else if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    /** 单个订单簿分片的串行执行与完成交接。由单一 Owner 提交、独立 MatcherThread 执行、Owner 按本分片顺序回收传输槽。 */
    @Slf4j
    public static final class ShardPipeline implements AutoCloseable {
        private static final VarHandle CURSORS = MethodHandles.arrayElementVarHandle(long[].class);
        private static final int SUBMITTED = 0, CONSUMED = 16, COMPLETED = 32;
        private final long[] cursors = new long[48];
        private static final int IDLE_SPINS = 1_024;
        private static final long IDLE_PARK_NANOS = 1_000L;
        private Slot[] slots;
        private final int requestedCapacity;
        private final int mask;
        private volatile Thread worker;
        private Runnable startupAction;
        private long cachedConsumedPosition;
        private long cachedCompletedPosition;
        private volatile boolean accepting;
        @Accessors(fluent = true, chain = false)
        @Getter
        private volatile int submissionHighWaterMark;
        @Accessors(fluent = true, chain = false)
        @Getter
        private volatile int completionHighWaterMark;
        private volatile boolean started;
        private volatile Throwable startupFailure;
        private volatile Throwable publicationFailure;
        private long controlSequence;
        private Runnable shutdownAction;
        private volatile Throwable shutdownFailure;
        private final int workerId;
        private OneToOneConcurrentArrayQueue<BackgroundRead<?>> backgroundReads;
        private final Runnable completionPublished;

        public <T> java.util.concurrent.CompletableFuture<T> readAtSubmissionFence(Supplier<T> read) {
            if (!accepting) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new RejectedExecutionException("matcher read mailbox unavailable"));
            }
            var future = new java.util.concurrent.CompletableFuture<T>();
            if (!backgroundReads.offer(new BackgroundRead<>((long) CURSORS.getVolatile(cursors, SUBMITTED), read, future))) {
                future.completeExceptionally(new RejectedExecutionException("matcher read mailbox unavailable"));
            }
            return future;
        }

        private record BackgroundRead<T>(long fence, Supplier<T> read, java.util.concurrent.CompletableFuture<T> future) {
            void execute() {
                try {
                    future.complete(read.get());
                } catch (RuntimeException | Error failure) {
                    future.completeExceptionally(failure);
                    if (failure instanceof Error fatal) throw fatal;
                }
            }
        }

        public ShardPipeline(int workerId, int requestedCapacity, Runnable completionPublished) {
            this.completionPublished = completionPublished;
            if (requestedCapacity <= 0 || (requestedCapacity & (requestedCapacity - 1)) != 0) {
                throw new IllegalArgumentException("matcher pipeline capacity must be a power of two");
            }
            this.workerId = workerId;
            this.requestedCapacity = requestedCapacity;
            mask = requestedCapacity - 1;
        }

        public synchronized void start(Runnable action, TradingCpuAffinity affinity) {
            if (worker != null) return;
            backgroundReads = new OneToOneConcurrentArrayQueue<>(requestedCapacity);
            slots = new Slot[requestedCapacity];
            for (int index = 0; index < requestedCapacity; index++) slots[index] = new Slot();
            startupAction = action;
            accepting = true;
            MatcherThread matcherWorker = new MatcherThread(() -> run(affinity), "core-matcher-" + workerId, workerId);
            worker = matcherWorker;
            matcherWorker.start();
            awaitStartup();
        }

        public void submit(long coreSequence, Supplier<?> command, TradeSettlement settlement) {
            if (coreSequence <= 0 || command == null) {
                throw new IllegalArgumentException("matcher pipeline command is invalid");
            }
            submitInternal(coreSequence, command, settlement, null);
        }

        void submit(long coreSequence, Supplier<?> command,
                    TradeSettlement settlement,
                    OrderCommand resultTarget) {
            if (coreSequence <= 0 || resultTarget == null || settlement != null && settlement.direct())
                throw new IllegalArgumentException("invalid command result target");
            submitInternal(coreSequence, command, settlement, resultTarget);
        }

        public long submitControl(Supplier<?> command) {
            controlSequence = Math.incrementExact(controlSequence);
            long token = -controlSequence;
            submitInternal(token, command, null, null);
            return token;
        }

        public Object pollControl(long token) {
            if (token >= 0) throw new IllegalArgumentException("matcher control token must be negative");
            return pollResult(token);
        }

        private void submitInternal(long token, Supplier<?> command,
                                    TradeSettlement settlement,
                                    OrderCommand resultTarget) {
            if (token == 0 || command == null) throw new IllegalArgumentException("matcher command is invalid");
            rethrowPublicationFailure();
            if (!accepting) throw new RejectedExecutionException("matcher pipeline is closed");
            skipReadyDirectSlots();
            long position = (long) CURSORS.getVolatile(cursors, SUBMITTED);
            if (position - cachedConsumedPosition >= slots.length) {
                cachedConsumedPosition = (long) CURSORS.getVolatile(cursors, CONSUMED);
                if (position - cachedConsumedPosition >= slots.length) {
                    throw new RejectedExecutionException("matcher pipeline is full");
                }
            }
            Slot slot = slots[(int) position & mask];
            if (slot.command != null || slot.result != null || slot.failure != null || slot.token != 0) {
                throw new IllegalStateException("matcher pipeline slot was not released");
            }
            slot.token = token;
            slot.command = command;
            slot.settlement = settlement;
            slot.resultTarget = resultTarget;
            slot.directSettlement = settlement != null && settlement.direct();
            CURSORS.setVolatile(cursors, SUBMITTED, position + 1);
            int depth = Math.toIntExact(position + 1 - cachedConsumedPosition);
            if (depth > submissionHighWaterMark) {
                cachedConsumedPosition = (long) CURSORS.getVolatile(cursors, CONSUMED);
                depth = Math.toIntExact(position + 1 - cachedConsumedPosition);
                if (depth > submissionHighWaterMark) {
                    submissionHighWaterMark = depth;
                }
            }
        }

        public OrderMatchingResult.Control poll(long expectedCoreSequence) {
            if (expectedCoreSequence <= 0) throw new IllegalArgumentException("matcher sequence must be positive");
            Object result = pollResult(expectedCoreSequence);
            if (result == null) return null;
            if (result instanceof OrderMatchingResult.Control matchingResult) return matchingResult;
            throw new IllegalStateException("matcher pipeline returned an invalid matching result: "
                    + result.getClass().getName());
        }

        public long completedMatchingSequence() {
            while (true) {
                rethrowPublicationFailure();
                skipReadyDirectSlots();
                long position = (long) CURSORS.getVolatile(cursors, CONSUMED);
                if (position >= cachedCompletedPosition) {
                    cachedCompletedPosition = (long) CURSORS.getVolatile(cursors, COMPLETED);
                    if (position >= cachedCompletedPosition) return 0;
                }
                Slot slot = slots[(int) position & mask];
                if (slot.token > 0 && slot.directSettlement) continue;
                return slot.token > 0 ? slot.token : 0;
            }
        }

        private Object pollResult(long expectedToken) {
            rethrowPublicationFailure();
            if (expectedToken == 0) throw new IllegalArgumentException("matcher token must be non-zero");
            long position = (long) CURSORS.getVolatile(cursors, CONSUMED);
            if (position >= cachedCompletedPosition) {
                cachedCompletedPosition = (long) CURSORS.getVolatile(cursors, COMPLETED);
                if (position >= cachedCompletedPosition) return null;
            }
            Slot slot = slots[(int) position & mask];
            if (slot.token != expectedToken) return null;
            if (slot.command == null) throw new IllegalStateException("matcher completion publication gap");
            Object result = slot.result;
            Throwable failure = slot.failure;
            slot.clear();
            CURSORS.setVolatile(cursors, CONSUMED, position + 1);
            if (failure != null) {
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (failure instanceof Error error) throw error;
                throw new IllegalStateException("matcher pipeline command failed", failure);
            }
            if (result == null) throw new IllegalStateException("matcher pipeline returned no result");
            skipReadyDirectSlots();
            return result;
        }

        private void skipReadyDirectSlots() {
            while (true) {
                long position = (long) CURSORS.getVolatile(cursors, CONSUMED);
                if (position >= cachedCompletedPosition) {
                    cachedCompletedPosition = (long) CURSORS.getVolatile(cursors, COMPLETED);
                    if (position >= cachedCompletedPosition) return;
                }
                Slot slot = slots[(int) position & mask];
                if (slot.token <= 0 || !slot.directSettlement) return;
                if (slot.failure != null) {
                    pollResult(slot.token);
                    return;
                }
                slot.clear();
                CURSORS.setVolatile(cursors, CONSUMED, position + 1);
            }
        }

        public int submissionDepth() {
            return Math.toIntExact((long) CURSORS.getVolatile(cursors, SUBMITTED) - (long) CURSORS.getVolatile(cursors, COMPLETED));
        }

        public int completionDepth() {
            return Math.toIntExact((long) CURSORS.getVolatile(cursors, COMPLETED) - (long) CURSORS.getVolatile(cursors, CONSUMED));
        }

        public int capacity() {
            return requestedCapacity;
        }

        private void run(TradingCpuAffinity configuration) {
            try (var affinity = configuration.matcher(workerId)) {
                runMatching();
            } catch (Throwable failure) {
                if (!started) {
                    startupFailure = failure;
                    started = true;
                } else {
                    publicationFailure = failure;
                    Runnable signal = completionPublished;
                    if (signal != null) signal.run();
                }
                accepting = false;
            }
        }

        private void runMatching() {
            try {
                if (startupAction != null) startupAction.run();
            } catch (Throwable failure) {
                startupFailure = failure;
                accepting = false;
            } finally {
                started = true;
            }
            if (startupFailure != null) return;
            long position = (long) CURSORS.getVolatile(cursors, COMPLETED);
            long cachedSubmitted = (long) CURSORS.getVolatile(cursors, SUBMITTED);
            long cachedConsumed = (long) CURSORS.getVolatile(cursors, CONSUMED);
            try {
                while (accepting || position < (cachedSubmitted = (long) CURSORS.getVolatile(cursors, SUBMITTED))) {
                    BackgroundRead<?> read = backgroundReads.peek();
                    if (read != null) {
                        cachedSubmitted = (long) CURSORS.getVolatile(cursors, SUBMITTED);
                        if (position >= read.fence()) {
                            backgroundReads.poll();
                            read.execute();
                            continue;
                        }
                    }
                    if (position >= cachedSubmitted) {
                        cachedSubmitted = (long) CURSORS.getVolatile(cursors, SUBMITTED);
                        if (position >= cachedSubmitted) {
                            Thread.onSpinWait();
                            continue;
                        }
                    }
                    Slot slot = slots[(int) position & mask];
                    Supplier<?> command = slot.command;
                    if (command == null || slot.token == 0) {
                        slot.failure = new IllegalStateException("matcher command publication gap");
                    } else {
                        TradeSettlement settlement = slot.settlement;
                        try {
                            if (settlement != null && settlement.direct()) settlement.beginMatcherPublication();
                            Object result = command.get();
                            slot.result = slot.token > 0 && result instanceof OrderMatchingResult.Control matchingResult
                                    ? matchingResult.withCoreSequenceInPlace(slot.token) : result;
                            if (settlement != null && !settlement.resultPrepared()) {
                                throw new IllegalStateException("direct matcher did not publish its native result");
                            }
                        } catch (Throwable failure) {
                            if (settlement != null) settlement.failDirect(failure);
                            slot.failure = failure;
                        } finally {
                            if (settlement != null && settlement.direct()) settlement.completeMatcherPublication();
                        }
                    }
                    OrderCommand target = slot.resultTarget;
                    OrderMatchingResult.Control routedResult = target != null && slot.failure == null
                            ? (OrderMatchingResult.Control) slot.result : null;
                    if (routedResult != null) {
                        try { target.publishMatcherResult(workerId, routedResult); }
                        catch (Throwable failure) {
                            publicationFailure = failure;
                            accepting = false;
                        }
                    }
                    position++;
                    CURSORS.setVolatile(cursors, COMPLETED, position);
                    Runnable signal = completionPublished;
                    if (signal != null) signal.run();
                    if (position - cachedConsumed > completionHighWaterMark) {
                        cachedConsumed = (long) CURSORS.getVolatile(cursors, CONSUMED);
                        int compDepth = Math.toIntExact(position - cachedConsumed);
                        if (compDepth > completionHighWaterMark) {
                            completionHighWaterMark = compDepth;
                        }
                    }
                    if (publicationFailure != null) break;
                }
            } catch (Throwable failure) {
                publicationFailure = failure;
                accepting = false;
                Runnable signal = completionPublished;
                if (signal != null) signal.run();
            }
            BackgroundRead<?> unfinishedRead;
            while ((unfinishedRead = backgroundReads.poll()) != null) {
                unfinishedRead.future().completeExceptionally(new RejectedExecutionException("matcher stopped"));
            }
            if (shutdownAction != null) {
                try {
                    shutdownAction.run();
                } catch (Throwable failure) {
                    shutdownFailure = failure;
                }
            }
        }

        private void rethrowPublicationFailure() {
            Throwable failure = publicationFailure;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("matcher result publication failed", failure);
        }

        private void awaitStartup() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            int idle = 0;
            while (!started && System.nanoTime() < deadline) {
                if (idle++ < IDLE_SPINS) Thread.onSpinWait();
                else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
            }
            if (!started) {
                accepting = false;
                throw new IllegalStateException("matcher pipeline startup timed out");
            }
            if (startupFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (startupFailure instanceof Error error) throw error;
            if (startupFailure != null) {
                throw new IllegalStateException("matcher pipeline startup failed", startupFailure);
            }
        }

        public boolean workerStopped() { return worker == null || !worker.isAlive(); }

        @Override
        public void close() {
            close(null);
        }

        public void close(Runnable action) {
            Thread activeWorker = worker;
            if (activeWorker == null) {
                accepting = false;
                if (action != null) action.run();
                return;
            }
            if (!accepting) {
                if (activeWorker.isAlive()) {
                    try {
                        activeWorker.join(TimeUnit.SECONDS.toMillis(5));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("matcher pipeline shutdown was interrupted", exception);
                    }
                }
                if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
                if (action != null) action.run();
                return;
            }
            shutdownAction = action;
            accepting = false;
            LockSupport.unpark(activeWorker);
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (activeWorker.isAlive() && System.nanoTime() < deadline) {
                try {
                    activeWorker.join(10);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (activeWorker.isAlive()) throw new IllegalStateException("matcher pipeline did not stop");
            if (shutdownFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (shutdownFailure instanceof Error error) throw error;
            if (shutdownFailure != null) {
                throw new IllegalStateException("matcher pipeline shutdown failed", shutdownFailure);
            }
        }

        private static final class Slot {
            private long token;
            private Supplier<?> command;
            private OrderCommand resultTarget;
            private Object result;
            private Throwable failure;
            private TradeSettlement settlement;
            private boolean directSettlement;

            private void clear() {
                token = 0;
                command = null;
                resultTarget = null;
                result = null;
                failure = null;
                settlement = null;
                directSettlement = false;
            }
        }
    }
}
