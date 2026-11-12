package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.matcher.OrderMatchingResult;
import com.surprising.aeron.service.order.OrderCommand;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCore.*;

/** Owner 上的一致性快照流程：先建立屏障并收齐待撮合/待结算命令，再物化状态和哈希，捕获各 Matcher 原生模块，审计配对后同步编码并交出分段字节。线程与跨 Lane 读取权限沿用现有 Owner 边界。参数 owner：所属交易 Owner 运行时。 */
@RequiredArgsConstructor
public final class CoreSnapshotLifecycle {
    /** 所属交易 Owner 运行时，保存协作入口而不复制账户或订单状态。 */
    final TradingCore owner;

    @Accessors(fluent = true, chain = false)
    @Getter
    final AtomicReference<CompletionStage<OrderBookSnapshot>> inFlightMatcherSnapshot =
            new AtomicReference<>();

    /** 当前 Owner 正在推进的快照屏障，返回结果或释放后清空。返回当前屏障引用，未开始或已释放时为空，供 Owner 内部协作。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    SnapshotFence snapshotFence;

    /** 最近完成或恢复时登记的快照编号，用于选择后续编号。 */
    @Accessors(fluent = true, chain = false)
    @Setter
    long lastSnapshotId;

    /** 在各 Matcher 提交栅栏捕获原生模块，全部完成后汇总。 */
    public CompletionStage<OrderBookSnapshot> captureMatcherSnapshot(
            long snapshotId, long coreSequence, long businessStateHash,
            TradingCoreState state, Iterable<CoreOrderState> activeOrders) {
        return owner.matcherPipeline().readEachAsync(shardId ->
                        owner.matchingAdapter().captureShardSnapshot(shardId, snapshotId, coreSequence, activeOrders))
                .thenApply(shards -> owner.matchingAdapter().finishShardedSnapshot(
                        snapshotId, coreSequence, businessStateHash, state, shards))
                .whenComplete((snapshot, failure) -> {
                    if (failure != null) owner.matchingAdapter().discardSnapshot(snapshotId);
                }).minimalCompletionStage();
    }

    /** 选取同时大于最近快照号和已应用命令数的正编号，精确加法溢出时抛异常，再走完整同步快照流程。 */
    public byte[] snapshot() {
        return snapshot(Math.max(Math.addExact(lastSnapshotId, 1),
                Math.max(1, Math.addExact(owner.appliedCommandCount(), 1))));
    }

    /** 必要时激活运行时，检查健康并建立独立使用的超时屏障。 */
    public byte[] snapshot(long snapshotId) {
        if (!owner.activated()) owner.activate();
        owner.assertHealthy();
        long deadlineNanos = Math.addExact(System.nanoTime(), java.util.concurrent.TimeUnit.SECONDS.toNanos(
                owner.configuration().snapshotTimeoutSeconds()));
        beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = pollSnapshotSections(0, 0, System.nanoTime());
            if (snapshot != null) return snapshot.toByteArray();
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** 必要时激活后检查 Owner 身份、健康和参数。 */
    public void beginSnapshot(long snapshotId, long deadlineNanos) {
        if (!owner.activated()) owner.activate();
        owner.assertOwner();
        owner.assertHealthy();
        if (snapshotId <= 0 || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid snapshot fence");
        }
        if (owner.directCommand.active() || owner.commits.controlPending(owner.pendingMatching().firstSequence()))
            throw new TradingCore.SnapshotNotReadyException();
        if (snapshotFence != null) {
            if (snapshotFence.snapshotId == snapshotId) {
                snapshotFence.deadlineNanos = Math.min(snapshotFence.deadlineNanos, deadlineNanos);
                return;
            }
            throw new TradingCore.SnapshotNotReadyException();
        }
        if (inFlightMatcherSnapshot.get() != null) throw new TradingCore.SnapshotNotReadyException();
        snapshotFence = new SnapshotFence(snapshotId, deadlineNanos);
    }

    /** 由 Owner 推进屏障：先检查中断/截止，再按首命令顺序完成 Matcher 和 Lane 结算，要求没有待命令/补丁并通过运行时就绪检查。 */
    public SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(
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
            throw new TradingCore.SnapshotFenceTimeoutException();
        }
        try {
            owner.matchingProgress.drainMatchingCompletions();
            while (!owner.pendingMatching().isEmpty()) {
                long sequence = owner.pendingMatching().firstSequence();
                OrderCommand pending = owner.pendingMatching().get(sequence);
                OrderMatchingResult result =
                        pending != null && pending.settlementEvent() != null
                                ? pending.matchingResult()
                                : pending.takeMatchingCompletion();
                if (result == null && pending != null && pending.settlementEvent() != null
                        && pending.settlementEvent().direct() && pending.settlementEvent().ready())
                    result = pending.settlementEvent().directResult();
                if (result == null) return null;
                if (owner.commits.completeMatching(sequence, result, clusterTimestamp, clusterPosition) == null) return null;
                owner.matchingProgress.drainMatchingCompletions();
            }
            if (owner.pendingMatching().inFlight() != 0) {
                throw new IllegalStateException("snapshot fence contains unfinished lane or matcher work");
            }
            if (owner.snapshotHasPendingCommands()
                    || owner.commits.publication.deferred() || owner.commits.publication.dirty()) {
                throw new IllegalStateException("snapshot fence contains unfinished commands or patch work");
            }
            owner.accountBook().requireSnapshotFenceReady();
            boolean acquired = owner.accountBook().accountLanesStarted() && !owner.accountBook().ownerLaneAccess();
            try {
                if (acquired) {
                    while (!owner.accountBook().tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
                }
                if (fence.snapshotState == null) {
                    fence.snapshotState = owner.tradingState();
                    fence.feePolicies = owner.accountBook().feePoliciesSnapshot();
                    fence.pendingTransfers = owner.accountBook().pendingTransfersSnapshot();
                    long businessStateHash = TradingCore.canonicalBusinessStateHash(
                            fence.snapshotState.businessStateHash(), fence.feePolicies, fence.pendingTransfers);
                    fence.businessStateHash = businessStateHash;
                }
                if (fence.matcherSnapshot == null) {
                    fence.coreSequence = owner.appliedCommandCount();
                    CompletionStage<OrderBookSnapshot> matcherSnapshot = captureMatcherSnapshot(
                            fence.snapshotId, fence.coreSequence, fence.businessStateHash,
                            fence.snapshotState, fence.snapshotState.orders().values().stream().filter(order -> order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN).toList());
                    if (!inFlightMatcherSnapshot.compareAndSet(null, matcherSnapshot)) {
                        throw new TradingCore.SnapshotNotReadyException();
                    }
                    fence.matcherSnapshot = matcherSnapshot.toCompletableFuture();
                    matcherSnapshot.whenComplete((ignored, failure) ->
                            inFlightMatcherSnapshot.compareAndSet(matcherSnapshot, null));
                }
                if (!fence.matcherSnapshot.isDone()) return null;
                OrderBookSnapshot matcherSnapshot = fence.matcherSnapshot.getNow(null);
                if (matcherSnapshot == null || owner.appliedCommandCount() != fence.coreSequence || !owner.pendingMatching().isEmpty()) {
                    throw new IllegalStateException("snapshot fence state changed during capture");
                }
                CoreSnapshotImage image = SectionedCoreSnapshotWriter.capture(owner, matcherSnapshot, fence.snapshotId,
                        fence.coreSequence, clusterTimestamp, clusterPosition, fence.snapshotState,
                        fence.feePolicies, fence.pendingTransfers, fence.businessStateHash);
                SectionedCoreSnapshotCodec.SectionedSnapshot encoded = SectionedCoreSnapshotWriter.encode(
                        image, owner.configuration().snapshot().maxBytes());
                lastSnapshotId = Math.max(lastSnapshotId, fence.snapshotId);
                snapshotFence = null;
                return encoded;
            } finally {
                if (acquired) owner.accountBook().releaseOwnerLaneAccess();
            }
        } catch (RuntimeException failure) {
            releaseSnapshotFence();
            throw failure;
        }
    }

    /** 清掉 Owner 的屏障引用。 */
    public void releaseSnapshotFence() {
        snapshotFence = null;
    }

    /** Owner 持有的一次快照进度：记录编号、截止时间、首次物化状态、水位与 Matcher 捕获 Future。它不拥有独立线程，也不把账户或订单簿变成另一个可写权威。 */
    public static final class SnapshotFence {
        final long snapshotId;
        /** 本次屏障按 System.nanoTime 比较的截止点，同号重入只能收紧。 */
        long deadlineNanos;
        long coreSequence = -1;
        @Accessors(fluent = true, chain = false)
        @Getter
        TradingCoreState snapshotState;
        long businessStateHash;
        java.util.Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies;
        java.util.Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers;
        CompletableFuture<OrderBookSnapshot> matcherSnapshot;
        /** 保存本次编号与单调时钟截止点，水位初始负一表示尚未捕获。 */
        SnapshotFence(long snapshotId, long deadlineNanos) {
            this.snapshotId = snapshotId;
            this.deadlineNanos = deadlineNanos;
        }

}

}
