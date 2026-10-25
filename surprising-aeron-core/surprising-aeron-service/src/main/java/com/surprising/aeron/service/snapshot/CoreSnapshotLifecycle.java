package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.MatchingResult;
import com.surprising.aeron.service.order.CommandSlot;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import static com.surprising.aeron.service.core.TradingCoreRuntime.*;

/**
 * Owner 上的一致性快照流程：先建立屏障并收齐待撮合/待结算命令，再物化状态和哈希，捕获各 Matcher 原生模块，审计配对后提交编码 Future。轮询完成才交出字节；释放屏障请求取消编码 Future，不自动取消正在运行的 Matcher 捕获。线程与跨 Lane 读取权限沿用现有 Owner 边界。
 * 构造约定：保存所属交易运行时引用，不复制交易状态，也不在构造器里激活 Matcher。
 * 参数 owner：所属交易 Owner 运行时
 */
@RequiredArgsConstructor
public final class CoreSnapshotLifecycle {
    /** 所属交易 Owner 运行时，保存协作入口而不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    /**
     *  已建立屏障后调用的原生撮合快照捕获函数，默认实现通过 Matcher 提交栅栏读取执行。
     * 替换原生撮合快照捕获入口，不在此检查非空，调用方负责初始化和线程使用边界。
     * @param matcherSnapshotCapture 后续屏障使用的 Matcher 捕获实现
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    TradingCoreRuntime.MatcherSnapshotCapture matcherSnapshotCapture;

    /**
     *  通过审计后接收快照镜像并返回分段结果 Future 的编码入口。
     * 替换快照编码入口，不在此检查非空或启动编码；实际轮询要求返回非空 Future。
     * @param snapshotEncoder 后续屏障使用的快照编码实现
     */
    @Accessors(fluent = true, chain = false)
    @Setter
    TradingCoreRuntime.SnapshotEncoder snapshotEncoder;

    /**  编码回调记录的首个运行异常，Owner 健康检查据此拒绝继续。 返回共享审计失败引用，编码回调只登记首个失败，运行时健康检查读取。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final AtomicReference<RuntimeException> snapshotAuditFailure = new AtomicReference<>();

    /**  当前仍在执行的 Matcher 捕获 Future，屏障释放不直接取消它。 返回正在捕获 Matcher 快照的原子引用，捕获完成后按同一 Future 身份比较清除。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    final AtomicReference<CompletableFuture<MatcherSnapshot>> inFlightMatcherSnapshot =
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
    public CompletableFuture<MatcherSnapshot> captureMatcherSnapshot(
            long snapshotId, long coreSequence, long businessStateHash,
            TradingCoreState state, Iterable<CoreOrderState> activeOrders) {
        return owner.matcherPipeline().readEachAsync(shardId ->
                        owner.matchingAdapter().captureShardSnapshot(shardId, snapshotId, coreSequence, activeOrders))
                .thenApply(shards -> owner.matchingAdapter().finishShardedSnapshot(
                        snapshotId, coreSequence, businessStateHash, state, shards))
                .whenComplete((snapshot, failure) -> {
                    if (failure != null) owner.matchingAdapter().discardSnapshot(snapshotId);
                });
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
     * 由 Owner 推进屏障：先检查中断/截止，再按首命令顺序完成 Matcher 和 Lane 结算，要求没有待命令/补丁并通过运行时就绪检查；必要时取得跨 Lane 读取权限，物化状态、捕获 Matcher、审计并启动编码。未完成返回空，编码完成下一次轮询返回；内部运行异常释放屏障，已登记的异步编码失败也交给健康检查。权限获取自旋期间不在本轮再次检查截止点。
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
            if (fence.encodedSnapshot != null) {
                if (!fence.encodedSnapshot.isDone()) return null;
                // 已经完成编码的分段快照，成功取出后推进最近快照编号。
                SectionedCoreSnapshotCodec.SectionedSnapshot encoded = fence.encodedSnapshot.join();
                if (encoded.length() > owner.configuration().snapshot().maxBytes())
                    throw new IllegalArgumentException("core snapshot exceeds deployment byte limit");
                lastSnapshotId = Math.max(lastSnapshotId, fence.snapshotId);
                snapshotFence = null;
                return encoded;
            }
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
            boolean acquired = (owner.runtimeState().accountLanesStarted() || owner.runtimeState().systemLedgerStarted()) && !owner.runtimeState().ownerLaneAccess();
            try {
                if (acquired) {
                    while (!owner.runtimeState().tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
                }
                if (fence.projectionSequence < 0) {
                    fence.projectionSequence = owner.commits.publication.publishedSequence();
                    fence.snapshotState = owner.tradingState();
                    // 首次物化状态连同当时费率及待划转重算的配对业务哈希。
                    long businessStateHash = TradingCoreRuntime.canonicalBusinessStateHash(
                            fence.snapshotState.businessStateHash(), owner.runtimeState().feePoliciesSnapshot(),
                            owner.runtimeState().pendingTransfersSnapshot());
                    fence.businessStateHash = businessStateHash;
                }
                if (fence.matcherSnapshot == null) {
                    fence.coreSequence = owner.appliedCommandCount();
                    // 本次新提交的原生捕获 Future，先登记到在途引用，再由完成回调按身份清除。
                    CompletableFuture<MatcherSnapshot> matcherSnapshot = matcherSnapshotCapture.capture(
                            fence.snapshotId, fence.coreSequence, fence.businessStateHash,
                            fence.snapshotState, fence.snapshotState.orders().values().stream().filter(/* 从首次物化订单表筛选出的候选订单，只保留 OPEN 状态交给原生对账。 */ order -> order.status() == com.surprising.aeron.model.CoreOrderStatus.OPEN).toList());
                    if (!inFlightMatcherSnapshot.compareAndSet(null, matcherSnapshot)) {
                        throw new TradingCoreRuntime.SnapshotNotReadyException();
                    }
                    fence.matcherSnapshot = matcherSnapshot;
                    matcherSnapshot.whenComplete((/* 本回调不读取成功值，只按原 Future 身份清除在途引用。 */ ignored, /* 本回调也不读取失败原因，成功或失败均结束该在途登记。 */ failure) ->
                            inFlightMatcherSnapshot.compareAndSet(matcherSnapshot, null));
                }
                if (!fence.matcherSnapshot.isDone()) return null;
                // 原生捕获已经完成的快照值，取出后仍需核对 Core 水位和待处理队列没有变化。
                MatcherSnapshot matcherSnapshot = fence.matcherSnapshot.getNow(null);
                if (matcherSnapshot == null || owner.appliedCommandCount() != fence.coreSequence || !owner.pendingMatching().isEmpty()) {
                    throw new IllegalStateException("snapshot fence state changed during capture");
                }
                // Matcher 完成且 Core 水位未变时再次从运行时捕获的编码输入，随后执行完整哈希审计。
                CoreSnapshotImage image = SectionedCoreSnapshotWriter.capture(owner, matcherSnapshot, fence.snapshotId,
                        fence.coreSequence, clusterTimestamp, clusterPosition);
                image.verifyFullState();
                fence.encodedSnapshot = snapshotEncoder.encode(image);
                if (fence.encodedSnapshot == null) {
                    throw new IllegalStateException("snapshot encoder returned no completion");
                }
                fence.encodedSnapshot.whenComplete((/* 此处不取编码成功值，结果留给 Owner 下一次轮询收集。 */ ignored, /* 编码完成的可空失败原因，非空时登记首个审计故障。 */ failure) -> {
                    if (failure == null) return;
                    // 编码失败拆开一层 CompletionException 后的原因。
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException
                            ? failure.getCause() : failure;
                    // 转换为 RuntimeException 的审计失败，原子引用只接受第一项。
                    RuntimeException auditFailure = cause instanceof RuntimeException /* 已经是运行异常的编码失败原因，可直接作为审计故障保存。 */ runtimeFailure
                            ? runtimeFailure : new IllegalStateException("snapshot audit failed", cause);
                    snapshotAuditFailure.compareAndSet(null, auditFailure);
                });
                return null;
            } finally {
                if (acquired) owner.runtimeState().releaseOwnerLaneAccess();
            }
        } catch (/* 捕获、屏障轮询或异步编码产生的异常，所在边界决定包装、释放或登记审计失败。 */ RuntimeException failure) {
            releaseSnapshotFence();
            throw failure;
        }
    }

    /**
     * 轮询分段结果，尚未完成返回空，已完成则额外合并为一个完整字节数组。
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     * @param nowNanos 当前轮询使用的单调时钟时间
     */
    public byte[] pollSnapshot(long clusterTimestamp, long clusterPosition, long nowNanos) {
        // 本次轮询已完成的可空分段结果。
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        return snapshot == null ? null : snapshot.toByteArray();
    }

    /**
     * 只尝试一次轮询：成功直接返回；尚未进入编码阶段时释放本屏障并报告未就绪，已在编码则保留屏障供之后继续轮询。
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     * @param nowNanos 当前轮询使用的单调时钟时间
     */
    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long clusterTimestamp, long clusterPosition, long nowNanos) {
        // 本次轮询已完成的可空分段结果。
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                pollSnapshotSections(clusterTimestamp, clusterPosition, nowNanos);
        if (snapshot != null) return snapshot;
        if (snapshotFence != null && snapshotFence.encodedSnapshot == null) releaseSnapshotFence();
        throw new TradingCoreRuntime.SnapshotNotReadyException();
    }

    /**
     * 有编码 Future 时请求 cancel，再清掉 Owner 的屏障引用；不清空或取消 inFlightMatcherSnapshot，捕获完成后的回调负责移除它。取消编码也可能经失败回调登记审计失败，不能理解为无副作用回滚。
     */
    public void releaseSnapshotFence() {
        if (snapshotFence != null && snapshotFence.encodedSnapshot != null) {
            snapshotFence.encodedSnapshot.cancel(true);
        }
        snapshotFence = null;
    }

    /**
     * Owner 持有的一次快照进度：记录编号、截止时间、首次物化状态、水位与两个 Future；它不拥有独立线程，也不把账户或订单簿变成另一个可写权威。
     */
    public static final class SnapshotFence {
        /** 本次屏障绑定的快照编号。 */
        final long snapshotId;
        /** 本次屏障按 System.nanoTime 比较的截止点，同号重入只能收紧。 */
        long deadlineNanos;
        /** 开始 Matcher 捕获时的已应用命令水位，负一表示尚未登记。 */
        long coreSequence = -1;
        /** 首次物化状态时保存的投影发布水位，负一表示尚未捕获。 */
        long projectionSequence = -1;
        /**  首次在快照读取边界物化的交易状态，只在该屏障生命周期持有。 返回首次物化后供 Matcher 配对使用的状态引用，尚未捕获时为空。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        TradingCoreState snapshotState;
        /** 首次物化状态连同费率及待划转的业务哈希，用于 Matcher 配对。 */
        long businessStateHash;
        /** 本屏障的 Matcher 捕获 Future，可能在屏障释放后仍继续执行。 */
        CompletableFuture<MatcherSnapshot> matcherSnapshot;
        /**  通过审计后启动的编码 Future，成功后交给持久化边界。 返回当前编码 Future 引用，尚未进入编码阶段时为空，不复制或阻止调用方操作该 Future。 */
        @Accessors(fluent = true, chain = false)
        @Getter
        CompletableFuture<SectionedCoreSnapshotCodec.SectionedSnapshot> encodedSnapshot;

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
