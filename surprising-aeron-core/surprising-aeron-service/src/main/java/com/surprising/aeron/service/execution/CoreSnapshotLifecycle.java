package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** 一致性快照生命周期：建立屏障、收集状态、编码及失败释放。 */
final class CoreSnapshotLifecycle {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    CoreSnapshotLifecycle(TradingCoreRuntime owner) { this.owner = owner; }

    /** 撮合状态捕获边界；在被验证的快照屏障内执行。 */
    TradingCoreRuntime.MatcherSnapshotCapture matcherSnapshotCapture;

    /** 快照编码边界；编码完成前保留必要的不可变输入。 */
    TradingCoreRuntime.SnapshotEncoder snapshotEncoder;

    /** 快照异步任务报告的审计失败；owner 健康检查读取。 */
    final AtomicReference<RuntimeException> snapshotAuditFailure = new AtomicReference<>();

    /** 正在收集的 matcher 快照；原子交接完成状态。 */
    final AtomicReference<CompletableFuture<MatcherSnapshot>> inFlightMatcherSnapshot =
            new AtomicReference<>();

    /** 进行中的一致性快照屏障；完成或失败后释放。 */
    SnapshotFence snapshotFence;

    /** 最近完成或恢复的快照 ID，生成后续快照 ID 时使用。 */
    long lastSnapshotId;

    CompletableFuture<MatcherSnapshot> captureMatcherSnapshot(
            long snapshotId, long coreSequence, long businessStateHash,
            TradingCoreState state, Iterable<CoreOrderState> activeOrders) {
        try {
            if (owner.matchingAdapter.topology().matchingEngineCount() == 1) {
                return CompletableFuture.completedFuture(owner.matcherPipeline.call(
                        () -> owner.matchingAdapter.snapshotAsync(snapshotId, coreSequence,
                                businessStateHash, state, activeOrders).join(),
                        TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS));
            }
            var shards = owner.matcherPipeline.callEach(shardId -> owner.matchingAdapter.captureShardSnapshot(
                    shardId, snapshotId, coreSequence, activeOrders), TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
            return CompletableFuture.completedFuture(owner.matchingAdapter.finishShardedSnapshot(
                    snapshotId, coreSequence, businessStateHash, state, shards));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public byte[] snapshot() {
        return snapshot(Math.max(Math.addExact(lastSnapshotId, 1),
                Math.max(1, Math.addExact(owner.appliedCommandCount, 1))));
    }

    public byte[] snapshot(long snapshotId) {
        if (!owner.activated) owner.activate();
        owner.assertHealthy();
        long deadlineNanos = Math.addExact(System.nanoTime(), java.util.concurrent.TimeUnit.SECONDS.toNanos(
                TradingCoreRuntime.STANDALONE_SNAPSHOT_TIMEOUT_SECONDS));
        beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = pollSnapshotSections(0, 0, System.nanoTime());
            if (snapshot != null) return snapshot.toByteArray();
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    void beginSnapshot(long snapshotId, long deadlineNanos) {
        if (!owner.activated) owner.activate();
        owner.assertOwner();
        owner.assertHealthy();
        if (snapshotId <= 0 || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid snapshot fence");
        }
        if (snapshotFence != null) {
            if (snapshotFence.snapshotId == snapshotId) {
                snapshotFence.deadlineNanos = Math.min(snapshotFence.deadlineNanos, deadlineNanos);
                return;
            }
            throw new TradingCoreRuntime.SnapshotNotReadyException();
        }
        if (inFlightMatcherSnapshot.get() != null) throw new TradingCoreRuntime.SnapshotNotReadyException();
        snapshotFence = new SnapshotFence(snapshotId, deadlineNanos);
    }

    SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        owner.assertOwner();
        owner.assertHealthy();
        SnapshotFence fence = snapshotFence;
        if (fence == null) throw new IllegalStateException("snapshot fence is not active");
        if (Thread.currentThread().isInterrupted()) {
            releaseSnapshotFence();
            throw new IllegalStateException("snapshot fence interrupted");
        }
        if (nowNanos >= fence.deadlineNanos) {
            releaseSnapshotFence();
            throw new TradingCoreRuntime.SnapshotFenceTimeoutException();
        }
        try {
            if (fence.encodedSnapshot != null) {
                if (!fence.encodedSnapshot.isDone()) return null;
                SectionedCoreSnapshotCodec.SectionedSnapshot encoded = fence.encodedSnapshot.join();
                lastSnapshotId = Math.max(lastSnapshotId, fence.snapshotId);
                snapshotFence = null;
                return encoded;
            }
            owner.drainMatchingCompletions();
            while (!owner.pendingMatching.isEmpty()) {
                long sequence = owner.firstPendingMatchingSequence();
                PendingMatching pending = owner.pendingMatching.get(sequence);
                com.surprising.aeron.service.matching.CoreMatchingResult result =
                        pending != null && (pending.settlementEvent() != null || pending.cancelEvent() != null
                                || pending.replaceEvent() != null)
                                ? owner.laneCommandContexts.required(sequence).matchingResult()
                                : owner.laneCommandContexts.required(sequence).takeMatchingCompletion();
                if (result == null) return null;
                if (owner.commits.completeMatching(sequence, result, clusterTimestamp, clusterPosition) == null) return null;
                owner.drainMatchingCompletions();
            }
            if (owner.laneCommandContexts.inFlight() != 0) {
                throw new IllegalStateException("snapshot fence contains unfinished lane or matcher work");
            }
            if (owner.currentAdmission != null
                    || owner.runtimeProjectionJournal.hasOutstandingReservation()
                    || owner.commits.commitPublicationDeferred || owner.commits.commitPublicationDirty) {
                throw new IllegalStateException("snapshot fence contains outstanding admission or patch work");
            }
            owner.runtimeState.requireSnapshotFenceReady();
            if (fence.projectionSequence < 0) {
                fence.projectionSequence = owner.runtimeProjectionJournal.publishedSequence();
                fence.snapshotState = com.surprising.aeron.service.state.RuntimeStateMaterializer.materialize(
                        owner.runtimeState, owner.identities);
                long businessStateHash = owner.canonicalBusinessStateHash(fence.snapshotState.businessStateHash());
                long fundsStateHash = com.surprising.aeron.service.state.RollingFundsStateHash.compute(
                        fence.snapshotState);
                fence.projection = new com.surprising.aeron.service.state.RuntimeCommitJournal.ProjectionVersion(
                        fence.projectionSequence, fence.snapshotState, businessStateHash, fundsStateHash);
            }
            var projection = fence.projection;
            if (fence.matcherSnapshot == null) {
                fence.coreSequence = owner.appliedCommandCount;
                CompletableFuture<MatcherSnapshot> matcherSnapshot = matcherSnapshotCapture.capture(
                        fence.snapshotId, fence.coreSequence, projection.businessStateHash(),
                        fence.snapshotState, owner.activeOrderIndex.orders());
                if (!inFlightMatcherSnapshot.compareAndSet(null, matcherSnapshot)) {
                    throw new TradingCoreRuntime.SnapshotNotReadyException();
                }
                fence.matcherSnapshot = matcherSnapshot;
                matcherSnapshot.whenComplete((ignored, failure) ->
                        inFlightMatcherSnapshot.compareAndSet(matcherSnapshot, null));
            }
            if (!fence.matcherSnapshot.isDone()) return null;
            MatcherSnapshot matcherSnapshot = fence.matcherSnapshot.getNow(null);
            if (matcherSnapshot == null || owner.appliedCommandCount != fence.coreSequence || !owner.pendingMatching.isEmpty()) {
                throw new IllegalStateException("snapshot fence state changed during capture");
            }
            CoreSnapshotImage image = SectionedCoreSnapshotCodec.capture(owner, matcherSnapshot, fence.snapshotId,
                    fence.coreSequence, clusterTimestamp, clusterPosition);
            image.verifyFullState();
            fence.encodedSnapshot = snapshotEncoder.encode(image);
            if (fence.encodedSnapshot == null) {
                throw new IllegalStateException("snapshot encoder returned no completion");
            }
            fence.encodedSnapshot.whenComplete((ignored, failure) -> {
                if (failure == null) return;
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        ? failure.getCause() : failure;
                RuntimeException auditFailure = cause instanceof RuntimeException runtimeFailure
                        ? runtimeFailure : new IllegalStateException("snapshot audit failed", cause);
                snapshotAuditFailure.compareAndSet(null, auditFailure);
            });
            return null;
        } catch (RuntimeException failure) {
            releaseSnapshotFence();
            throw failure;
        }
    }

    byte[] pollSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        return snapshot == null ? null : snapshot.toByteArray();
    }

    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        if (snapshotFence != null && snapshotFence.encodedSnapshot == null) releaseSnapshotFence();
        throw new TradingCoreRuntime.SnapshotNotReadyException();
    }

    void releaseSnapshotFence() {
        if (snapshotFence != null && snapshotFence.encodedSnapshot != null) {
            snapshotFence.encodedSnapshot.cancel(true);
        }
        snapshotFence = null;
    }

    static final class SnapshotFence {
        /** 本次快照 ID。 */
        final long snapshotId;
        /** 快照屏障的单调时钟截止时间。 */
        long deadlineNanos;
        /** 快照所覆盖的已完成命令序号。 */
        long coreSequence = -1;
        /** 快照所覆盖的导出提交水位。 */
        long projectionSequence = -1;
        /** 快照边界物化的状态，只在快照生命周期内持有。 */
        TradingCoreState snapshotState;
        /** 受版本保护的提交视图，快照结束时释放。 */
        com.surprising.aeron.service.state.RuntimeCommitJournal.ProjectionVersion projection;
        /** 正在收集的撮合快照 Future。 */
        CompletableFuture<MatcherSnapshot> matcherSnapshot;
        /** 正在编码的快照 Future，完成后交给持久化边界。 */
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encodedSnapshot;

        SnapshotFence(long snapshotId, long deadlineNanos) {
            this.snapshotId = snapshotId;
            this.deadlineNanos = deadlineNanos;
        }
    }
}
