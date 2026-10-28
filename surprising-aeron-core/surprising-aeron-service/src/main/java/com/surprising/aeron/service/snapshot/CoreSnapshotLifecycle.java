package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCoreRuntime.*;

/**
 * Owner 上的一致性快照流程：先建立屏障并收齐待撮合/待结算命令，再物化状态和哈希，捕获各 Matcher 原生模块，审计配对后同步编码并交出分段字节。释放屏障不取消正在运行的 Matcher 捕获。线程与跨 Lane 读取权限沿用现有 Owner 边界。
 * 构造约定：保存所属交易运行时引用，不复制交易状态，也不在构造器里激活 Matcher。
 * 参数 owner：所属交易 Owner 运行时
 */
@RequiredArgsConstructor
public final class CoreSnapshotLifecycle {
    /** 所属交易 Owner 运行时，保存协作入口而不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    /** 正在执行的 Matcher 捕获完成信号；外部取消等待视图不结束捕获，完成回调按身份清除。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final AtomicReference<CompletionStage<MatcherSnapshot>> inFlightMatcherSnapshot =
            new AtomicReference<>();

    /**  当前 Owner 正在推进的快照屏障，返回结果或释放后清空。 返回当前屏障引用，未开始或已释放时为空，供 Owner 内部协作。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    SnapshotFence snapshotFence;

    /**
     *  最近完成或恢复时登记的快照编号，用于选择后续编号。
     * 直接设置最近快照编号，不在此校验正数或单调递增；正常完成快照时另用 max 推进。
     * @param lastSnapshotId 恢复或内部协作直接写入的最近快照编号
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    long lastSnapshotId;

    /** 在各 Matcher 提交栅栏捕获原生模块，全部完成后汇总；屏障释放不取消捕获。 */
    public CompletionStage<MatcherSnapshot> captureMatcherSnapshot(
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

    /**
     * 选取同时大于最近快照号和已应用命令数的正编号，精确加法溢出时抛异常，再走完整同步快照流程。
     */
    public byte[] snapshot() {
        return snapshot(Math.max(Math.addExact(lastSnapshotId, 1),
                Math.max(1, Math.addExact(owner.appliedCommandCount(), 1))));
    }

    /**
     * 必要时激活运行时，检查健康并建立独立使用的超时屏障；以零 Cluster 时间/位置不断轮询，未完成时短 park，完成后合并字节返回。未完成、超时与中断的具体处理由轮询入口执行。
     * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
     */
    public byte[] snapshot(long snapshotId) {
        if (!owner.activated()) owner.activate();
        owner.assertHealthy();
        // 本次独立快照按固定超时计算的单调时钟截止点。
        long deadlineNanos = Math.addExact(System.nanoTime(), java.util.concurrent.TimeUnit.SECONDS.toNanos(
                owner.configuration().snapshotTimeoutSeconds()));
        beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            // 本次轮询已完成的可空分段结果。
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = pollSnapshotSections(0, 0, System.nanoTime());
            if (snapshot != null) return snapshot.toByteArray();
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /**
     * 必要时激活后检查 Owner 身份、健康和参数；直接命令或待完成控制命令存在时拒绝。相同编号的已有屏障只收紧截止点，其他屏障或 Matcher 捕获仍在途则拒绝；成功仅登记新屏障，不立即复制状态。
     * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
     * @param deadlineNanos 正的 System.nanoTime 截止点，轮询开始时检查
     */
    public void beginSnapshot(long snapshotId, long deadlineNanos) {
        if (!owner.activated()) owner.activate();
        owner.assertOwner();
        owner.assertHealthy();
        if (snapshotId <= 0 || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid snapshot fence");
        }
        if (owner.directCommand.active() || owner.commits.controlPending(owner.pendingMatching().firstSequence()))
            throw new TradingCoreRuntime.SnapshotNotReadyException();
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

    /**
     * 由 Owner 推进屏障：先检查中断/截止，再按首命令顺序完成 Matcher 和 Lane 结算，要求没有待命令/补丁并通过运行时就绪检查；必要时取得跨 Lane 读取权限，物化状态、捕获 Matcher、审计并同步编码。Matcher 未完成返回空，完成后本次轮询交出结果；运行异常释放屏障并直接传播。权限获取自旋期间不在本轮再次检查截止点。
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     * @param nowNanos 当前轮询使用的单调时钟时间
     */
    public SectionedCoreSnapshotCodec.SectionedSnapshot pollSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        owner.assertOwner();
        owner.assertHealthy();
        // 当前正在推进的 Owner 屏障引用，返回成功或释放时再清掉字段。
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
            owner.matchingProgress.drainMatchingCompletions();
            while (!owner.pendingMatching().isEmpty()) {
                // 待撮合队列中当前最早的 Core 命令序号，必须先推进该项。
                long sequence = owner.pendingMatching().firstSequence();
                // 当前最早序号对应的命令槽，保存结算事件或普通结果。
                CommandSlot pending = owner.pendingMatching().get(sequence);
                // 当前命令已发布的撮合事实，缺失或结算尚未结束时本次轮询返回空。
                MatchingResult result =
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
            owner.runtimeState().requireSnapshotFenceReady();
            // 本轮是否需要临时取得 Owner 对账户 Lane 的读取权限；成功取得后在 finally 释放。
            boolean acquired = owner.runtimeState().accountLanesStarted() && !owner.runtimeState().ownerLaneAccess();
            try {
                if (acquired) {
                    while (!owner.runtimeState().tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
                }
                if (fence.snapshotState == null) {
                    fence.snapshotState = owner.tradingState();
                    fence.feePolicies = owner.runtimeState().feePoliciesSnapshot();
                    fence.pendingTransfers = owner.runtimeState().pendingTransfersSnapshot();
                    // 同一栅栏捕获的状态、费率和划转贯穿 Matcher 配对、审计与编码。
                    long businessStateHash = TradingCoreRuntime.canonicalBusinessStateHash(
                            fence.snapshotState.businessStateHash(), fence.feePolicies, fence.pendingTransfers);
                    fence.businessStateHash = businessStateHash;
                }
                if (fence.matcherSnapshot == null) {
                    fence.coreSequence = owner.appliedCommandCount();
                    // 只登记生产者的完成信号，防止等待方取消自己的视图时提前放行交易。
                    CompletionStage<MatcherSnapshot> matcherSnapshot = captureMatcherSnapshot(
                            fence.snapshotId, fence.coreSequence, fence.businessStateHash,
                            fence.snapshotState, fence.snapshotState.orders().values().stream().filter(/* 从首次物化订单表筛选出的候选订单，只保留 OPEN 状态交给原生对账。 */ order -> order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN).toList());
                    if (!inFlightMatcherSnapshot.compareAndSet(null, matcherSnapshot)) {
                        throw new TradingCoreRuntime.SnapshotNotReadyException();
                    }
                    // 轮询视图可以独立结束，实际捕获完成前仍保留只读在途信号。
                    fence.matcherSnapshot = matcherSnapshot.toCompletableFuture();
                    matcherSnapshot.whenComplete((ignored, failure) ->
                            inFlightMatcherSnapshot.compareAndSet(matcherSnapshot, null));
                }
                if (!fence.matcherSnapshot.isDone()) return null;
                // 原生捕获已经完成的快照值，取出后仍需核对 Core 水位和待处理队列没有变化。
                MatcherSnapshot matcherSnapshot = fence.matcherSnapshot.getNow(null);
                if (matcherSnapshot == null || owner.appliedCommandCount() != fence.coreSequence || !owner.pendingMatching().isEmpty()) {
                    throw new IllegalStateException("snapshot fence state changed during capture");
                }
                // Matcher 完成且 Core 水位未变时复用首次冻结的编码输入，再执行完整哈希审计。
                CoreSnapshotImage image = SectionedCoreSnapshotWriter.capture(owner, matcherSnapshot, fence.snapshotId,
                        fence.coreSequence, clusterTimestamp, clusterPosition, fence.snapshotState,
                        fence.feePolicies, fence.pendingTransfers, fence.businessStateHash);
                image.verifyFullState();
                SectionedCoreSnapshotCodec.SectionedSnapshot encoded = SectionedCoreSnapshotWriter.encode(
                        image, owner.configuration().snapshot().maxBytes());
                lastSnapshotId = Math.max(lastSnapshotId, fence.snapshotId);
                snapshotFence = null;
                return encoded;
            } finally {
                if (acquired) owner.runtimeState().releaseOwnerLaneAccess();
            }
        } catch (/* 捕获、审计或编码异常释放屏障后直接传播。 */ RuntimeException failure) {
            releaseSnapshotFence();
            throw failure;
        }
    }

    /**
     * 清掉 Owner 的屏障引用；正在执行的 Matcher 捕获仍保留，完成回调负责清除在途信号。
     */
    public void releaseSnapshotFence() {
        snapshotFence = null;
    }

    /**
     * Owner 持有的一次快照进度：记录编号、截止时间、首次物化状态、水位与 Matcher 捕获 Future；它不拥有独立线程，也不把账户或订单簿变成另一个可写权威。
     */
    public static final class SnapshotFence {
        /** 本次屏障绑定的快照编号。 */
        final long snapshotId;
        /** 本次屏障按 System.nanoTime 比较的截止点，同号重入只能收紧。 */
        long deadlineNanos;
        /** 开始 Matcher 捕获时的已应用命令水位，负一表示尚未登记。 */
        long coreSequence = -1;
        /**  首次在快照读取边界物化的交易状态，只在该屏障生命周期持有。 返回首次物化后供 Matcher 配对使用的状态引用，尚未捕获时为空。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        TradingCoreState snapshotState;
        /** 首次物化状态连同费率及待划转的业务哈希，用于 Matcher 配对。 */
        long businessStateHash;
        java.util.Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies;
        java.util.Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers;
        /** 本屏障的 Matcher 捕获 Future，可能在屏障释放后仍继续执行。 */
        CompletableFuture<MatcherSnapshot> matcherSnapshot;
        /**
         * 保存本次编号与单调时钟截止点，水位初始负一表示尚未捕获；输入有效性由外层 beginSnapshot 检查。
         * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
         * @param deadlineNanos 正的 System.nanoTime 截止点，轮询开始时检查
         */
        SnapshotFence(long snapshotId, long deadlineNanos) {
            this.snapshotId = snapshotId;
            this.deadlineNanos = deadlineNanos;
        }

}

}
