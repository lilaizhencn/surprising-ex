package com.surprising.aeron.service.orchestration;

import lombok.extern.slf4j.Slf4j;
import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.aeron.service.orchestration.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import static com.surprising.aeron.service.orchestration.ClusterServiceEgress.NO_SESSION_ID;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

/**
 * 交易 Owner：按确定性边界推进交易，未完成命令与响应跨推进轮次保留。
 *
 * <p>该类不实现 {@link ClusteredService}。Aeron 回调、会话和网络出口由
 * {@link com.surprising.aeron.service.cluster.AeronTradingClusterService} 持有；Owner 只接收已复制的不可变命令、
 * 推进交易状态并产生终态交接。</p>
 */
public final class TradingCoreOwner {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TradingCoreOwner.class);

    /** 交易 Owner 的运行日志；仅记录生命周期和诊断信息，不进入交易状态计算。 */

    /** 快照阶段的最长等待时间；交易命令本身使用命令流水线的截止时间。 */
    private static final long SNAPSHOT_TIMEOUT_SECONDS = Long.getLong(
            "surprising.aeron.snapshot-timeout-seconds", 300L);

    /** 复制日志命令的执行阶段、依赖等待和提交边界的唯一状态持有者。 */
    private final OwnerCommandPipelineState commandPipeline;
    /** A fused command keeps this decoded slot until its terminal response is handed off. */
    private final com.surprising.aeron.service.command.order.DecodedMatchingCommand fusedDecoder;
    /** Cluster→Owner 唯一线程交接队列；Owner 直接 peek/推进，控制命令保持队首。 */
    private final OwnerIngressRing ingress = new OwnerIngressRing();
    private static final int INPUT_BATCH_SIZE = configuredInputBatchSize();

    /** 已提交响应的交接边界，不参与业务状态计算。 */
    private final ResponseSink responseSink;
    /** 实时盘口和快照的独立传输边界，不阻塞撮合主流程。 */
    private final TradingRealtimeBoundary realtimeBoundary;

    /** 已提交响应的显式出口交接函数。 */
    @FunctionalInterface
    interface ResponseSink {
        /** 接收已提交的只读响应；异步出口须保留 Header 租约直到编码完成。 */
        void offer(long sessionId, CoreMessageHeader header, CoreResponse response, long committedSequence);
    }

    /** 所属产品线；它决定命令、账户、撮合器和快照的隔离边界。 */
    private final ProductLine productLine;
    /** 当前产品线的权威交易运行时；Owner 只在自己的线程访问它。 */
    private TradingCoreRuntime state;
    /** Owner 线程可见的角色、日志时间和日志位置；不提供任何 Aeron 会话或发布器访问。 */
    private OwnerLogContext logContext;
    /** 集群等待策略，所有者是当前 Owner 线程。 */
    private IdleStrategy idleStrategy;
    /** Aeron 回调可能重入背景工作，使用该标记保护交易推进边界。 */
    private boolean processingLogCallback;

    /** 交易执行策略模式：FUSED（单线程极速就地执行）或 PIPELINED（流水线多线程执行）。 */
    private final TradingExecutionMode executionMode;

    /** Matcher 完成通知使用的统一回调，最终结果回到命令流水线状态。 */
    private final TradingCoreRuntime.MatchingCommitHandler matchingCommitHandler;

    /** 检查命令截止时间的分频掩码（每 1024 次自旋调用一次系统时钟）。 */
    private static final int DEADLINE_CHECK_MASK = 0x3FF;
    /** 命令截止时间检查分频计数器，仅在 Owner 线程内无锁自增。 */
    private int deadlineCheckCounter;

    TradingCoreOwner(ProductLine productLine, ResponseSink responseSink) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
        this.responseSink = java.util.Objects.requireNonNull(responseSink, "response sink");
        this.realtimeBoundary = new TradingRealtimeBoundary(productLine);
        this.executionMode = TradingExecutionMode.configured();
        boolean pipelined = executionMode == TradingExecutionMode.PIPELINED;
        commandPipeline = new OwnerCommandPipelineState(pipelined);
        matchingCommitHandler = commandPipeline::completeControlMatching;
        fusedDecoder = pipelined ? null
                : com.surprising.aeron.service.command.order.DecodedMatchingCommand.commandSlot();
    }

    /**
     * 启动 Owner 的集群边界和交易运行时。
     *
     * <p>这里只初始化运行时资源，不执行任何业务命令；业务命令必须来自已复制的 Cluster 日志。</p>
     */
    void start(OwnerLogContext logContext) {
        this.logContext = logContext;
        if (state == null) state = new TradingCoreRuntime(productLine);
        processingLogCallback = false;
        commandPipeline.reset();
        if (fusedDecoder != null) fusedDecoder.clearCommandSlot();
        idleStrategy = logContext.idle;
        log.info("Aeron core role productLine={} role={} mode={}", productLine, logContext.role, executionMode);
        realtimeBoundary.start(logContext.role, logContext.position, state);
    }

    OwnerIngressRing ingress() { return ingress; }

    private static int configuredInputBatchSize() {
        String property = "surprising.aeron.owner-input-batch-size";
        try {
            int value = Integer.parseInt(System.getProperty(property, "64").trim());
            if (value < 1 || value > 64) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(property + " must be an integer in [1,64]", failure);
        }
    }

    /** 保留实际输入环队首的准备结果；进入窗口或边界任务后释放临时解码。 */
    private void retainPendingPreparation() {
        var next = ingress.first();
        commandPipeline.retainPendingPreparation(next == null ? null : next.command);
    }

    /** 在 Owner 线程中绑定运行时并推进一轮命令状态。 */
    private void progressCommands() {
        state.bindOwner();
        state.runtimeState.enterAsynchronousCommandScope();
        try {
            if (executionMode == TradingExecutionMode.FUSED) {
                progressFusedCommands();
            } else {
                progressCommandsInScope();
            }
        } finally {
            state.runtimeState.exitAsynchronousCommandScope();
        }
    }

    /**
     * FUSED executes the ingress head without an in-flight window or dependency indexes.
     * The existing control continuation retains this same ingress entry until matching,
     * queries and commit publication have finished; yielding must never lose a terminal reply.
     */
    private void progressFusedCommands() {
        for (int completed = 0; completed < INPUT_BATCH_SIZE; completed++) {
            if (commandPipeline.hasActiveControl()) {
                if (!pollControl()) return;
                continue;
            }
            var next = ingress.first();
            if (next == null || next.action != null) return;
            logContext.timestamp = next.timestamp;
            logContext.position = next.position;
            if (state.requiresOwnerLaneAccessForPreparation(next.command)
                    && !state.runtimeState.tryAcquireOwnerLaneAccess()) return;
            state.assertClusterCallbackComplete();
            commandPipeline.beginControl(next);
            beginCapture(next.position, next.timestamp, next.command.header());
            commandPipeline.startProgressDeadline();
            boolean matching = TradingCoreRuntime.isMatchingCommand(next.command.header().messageType());
            commandPipeline.controlResponse(state.applyDecodedCommand(next.command, next.timestamp,
                    next.position, matching ? fusedDecoder : null, false, next.fingerprint));
            commandPipeline.rememberMatchingResponseSequence(matching ? state.matchingSequence(
                    next.command.header().commandIdMsb(), next.command.header().commandIdLsb()) : 0L);
            commandPipeline.recordAdmission();
            if (!pollControl()) return;
        }
    }

    /** 按“队首提交、异步控制、命令准入”的业务顺序执行一轮流水线。 */
    private void progressCommandsInScope() {
        boolean awaitingCompletion = false;
        int admittedInputs = 0;
        // 每轮先推进异步工作；随后连续 ready 的队首直接退休，不重复收集整个窗口。
        boolean advanceMatching = true;
        ClusterCommandWindow window = commandPipeline.commandWindow();
        for (int work = 0; work < OwnerCommandPipelineState.COMPLETION_BATCH_SIZE; work++) {
            // 当前命令的提交边界不变；未完成时仍可准入与在途命令无依赖的后续订单。
            if (!commandPipeline.hasCommittingHead() && window.size() != 0) beginCommandCommit();
            // 本轮未收到完成时仍准入独立命令；不为每个新准入项重跑整套完成收集。
            if (commandPipeline.hasCommittingHead() && !awaitingCompletion) {
                // 先提交已经就绪的结果；支持批量连续退休以加速滑动窗口排空。
                int committed = pollCommandCommitBatch(advanceMatching, OwnerCommandPipelineState.COMPLETION_BATCH_SIZE - work);
                if (committed > 0) {
                    advanceMatching = false;
                    work += committed - 1;
                    continue;
                }
                awaitingCompletion = true;
            }
            if (window.size() == window.capacity()) return;
            if (commandPipeline.hasActiveControl()) {
                if (!pollControl()) return;
                continue;
            }
            var next = ingress.first();
            if (next == null || next.action != null || admittedInputs == INPUT_BATCH_SIZE) return;
            if (commandPipeline.isIngressBlocked(next.command)) return;
            commandPipeline.clearBlockedIngress();
            logContext.timestamp = next.timestamp;
            logContext.position = next.position;
            if (!next.ownerObserved) {
                next.ownerObserved = true;
            }
            boolean eligible = state.prepareClusterPipelineScope(next.command, window);
            boolean retry = eligible && !state.pendingMatching.isEmpty()
                    && state.matchingSequence(next.command.header().commandIdMsb(), next.command.header().commandIdLsb()) != 0;
            int prefix = !eligible || retry ? window.size() : window.conflictingPrefixSize();
            if (prefix != 0) {
                commandPipeline.rememberBlockedIngress(next.command, prefix);
                // 记录等待决定，否则最繁忙的依赖等待会从诊断中消失。
                if (!eligible) commandPipeline.recordControlFence();
                else commandPipeline.recordDependencyFence(retry);
                if (commandPipeline.hasCommittingHead()) return;
                beginCommandCommit();
                continue;
            }
            if (!eligible) {
                if (state.requiresOwnerLaneAccessForPreparation(next.command)
                        && !state.runtimeState.tryAcquireOwnerLaneAccess()) return;
                commandPipeline.beginControl(next);
                state.assertClusterCallbackComplete();
                beginCapture(next.position, next.timestamp, next.command.header());
                commandPipeline.startProgressDeadline();
                commandPipeline.controlResponse(state.applyDecodedCommand(next.command, next.timestamp, next.position,
                        window.decodedIfPresent(next.command), false, next.fingerprint));
                commandPipeline.recordControlProgress();
                boolean matching = TradingCoreRuntime.isMatchingCommand(next.command.header().messageType());
                commandPipeline.rememberMatchingResponseSequence(matching ? state.matchingSequence(
                        next.command.header().commandIdMsb(), next.command.header().commandIdLsb()) : 0L);
                continue;
            }
            if (window.size() == 0) state.assertClusterCallbackComplete();
            var entry = window.add(next.sessionId, next.command, next.timestamp, next.position);
            var fingerprint = next.fingerprint;
            ingress.remove(); // The window now owns this command and its decoder lease.
            CoreResponse result = state.applyDecodedCommand(entry.request, entry.timestamp, entry.position,
                    window.decoded(entry.request), true, fingerprint);
            long sequence = result != null && result.resultCode() == TradingCoreRuntime.matchingPendingCode()
                    ? result.committedCoreSequence() : 0;
            window.bindSequence(entry, sequence);
            entry.response = sequence == 0 ? result : null;
            admittedInputs++;
            commandPipeline.recordAdmission();
            advanceMatching = true;
            // 准入命令后，如果刚才在等待完成，一旦当前队首就绪即刻放行，消除固定的滞后探测
            if (awaitingCompletion && state.commits.matchingCommitReady(
                    state.pendingMatching.get(commandPipeline.committingHead().sequence))) {
                awaitingCompletion = false;
                advanceMatching = false;
            }
        }
    }

    /** 在命令开始改变业务状态前建立实时事件捕获边界。未启用行情时快速短路。 */
    private void beginCapture(long position, long timestamp, CoreMessageHeader header) {
        if (realtimeBoundary.enabled()) {
            realtimeBoundary.beginCapture(state, position, timestamp, header);
        }
    }

    /** 每条命令有固定提交边界，不能让各副本的线程完成速度改变推送分组。 */
    private void beginCommandCommit() {
        commandPipeline.beginHeadCommit();
        var last = commandPipeline.committingHead();
        beginCapture(last.position, last.timestamp, last.request.header());
        commandPipeline.startProgressDeadline();
    }

    /** 公共推进按需执行；每条队首仍独立完成捕获、发布、响应和退休。 */
    private boolean pollCommandCommit(boolean advanceMatching) {
        return pollCommandCommitBatch(advanceMatching, 1) > 0;
    }

    /** 批量推进已就绪的队首命令提交；减少滑动窗口反压阻塞。 */
    private int pollCommandCommitBatch(boolean advanceMatching, int maxBatch) {
        int committedCount = 0;
        ClusterCommandWindow window = commandPipeline.commandWindow();
        while (committedCount < maxBatch) {
            if (!commandPipeline.hasCommittingHead()) {
                if (window.size() == 0) break;
                beginCommandCommit();
            }
            var last = commandPipeline.committingHead();
            long dispatchThrough = Math.max(last.sequence, window.lastMatchingSequence());
            boolean adv = advanceMatching || committedCount == 0;
            if (!adv && last.sequence != 0) {
                adv = !state.commits.matchingCommitReady(state.pendingMatching.get(last.sequence));
            }
            if (last.sequence != 0) {
                if (committedCount > 0 && !adv && !state.commits.matchingCommitReady(state.pendingMatching.get(last.sequence))) {
                    break;
                }
                CoreResponse terminalResponse = state.commits.commitReadyMatchingHead(
                        last.timestamp, last.position, last.sequence, dispatchThrough, adv);
                if (terminalResponse == null) {
                    checkProgressDeadline();
                    break;
                }
                if (last.response != null) throw new IllegalStateException("duplicate pipeline terminal response");
                last.response = terminalResponse;
            }
            if (window.size() == 1) state.assertClusterCallbackComplete();
            if (realtimeBoundary.enabled()) {
                realtimeBoundary.commit(state, last.position);
            }
            if (last.response == null) throw new IllegalStateException("missing pipeline terminal response");
            if (last.sessionId != NO_SESSION_ID) offerResponse(last.sessionId, last.request, last.response);
            commandPipeline.finishHeadCommit();
            state.runtimeState.releaseCompletedSequentialLaneStage();
            committedCount++;
            advanceMatching = false;
        }
        if (window.size() == 0 && realtimeBoundary.enabled()) progressRealtimeReads(System.nanoTime());
        return committedCount;
    }

    /** 推进单条控制命令的异步子任务、查询结果和终态提交。 */
    private boolean pollControl() {
        OwnerIngressRing.Entry activeControl = commandPipeline.activeControl();
        var request = activeControl.command;
        if (state.directCommand.active()) {
            commandPipeline.controlResponse(state.directCommand.poll(state));
            if (commandPipeline.controlResponse() == null) { checkProgressDeadline(); return false; }
        }
        state.commits.commitReadyMatching(OwnerCommandPipelineState.COMPLETION_BATCH_SIZE,
                activeControl.timestamp, activeControl.position, false, matchingCommitHandler);
        if (state.pendingMatching.firstSequence() != 0) { checkProgressDeadline(); return false; }
        if (commandPipeline.hasMatchingResponseSequence()) {
            if (commandPipeline.matchingResponse() == null) {
                throw new IllegalStateException("missing terminal callback result");
            }
            commandPipeline.controlResponse(commandPipeline.matchingResponse());
        }
        if (state.bookQueries.hasPendingQueries()) {
            long querySequence = state.bookQueries.querySequence(request.header().commandId());
            if (querySequence != 0) {
                CoreResponse result = state.bookQueries.takeQueryResult(querySequence);
                if (result == null) { checkProgressDeadline(); return false; }
                commandPipeline.controlResponse(result);
            }
        }
        state.assertClusterCallbackComplete();
        if (realtimeBoundary.enabled()) {
            realtimeBoundary.commit(state, activeControl.position);
        }
        if (activeControl.sessionId != NO_SESSION_ID) {
            offerResponse(activeControl.sessionId, request, commandPipeline.controlResponse());
        }
        state.runtimeState.releaseOwnerLaneAccess();
        commandPipeline.finishControl();
        ingress.remove();
        if (fusedDecoder != null) fusedDecoder.clearCommandSlot();
        else if (commandPipeline.commandWindow().decodedIfPresent(request) != null)
            commandPipeline.commandWindow().releaseDecoded();
        request.releaseDecoderSlot();
        commandPipeline.recordControlProgress();
        return true;
    }

    /** 检查异步命令是否被中断或超过允许的等待时间。采用分频采样减少高频系统时钟调用。 */
    private void checkProgressDeadline() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("asynchronous command progress interrupted: "
                    + stalledCommandDiagnostics());
        }
        if ((deadlineCheckCounter++ & DEADLINE_CHECK_MASK) == 0
                && System.nanoTime() - commandPipeline.progressDeadline() >= 0) {
            throw new IllegalStateException("asynchronous command progress timed out: "
                    + stalledCommandDiagnostics());
        }
    }

    /** Capture the existing Owner and worker state only when progress has already failed. */
    String stalledCommandDiagnostics() {
        var window = commandPipeline.commandWindow();
        var head = window == null || window.size() == 0 ? null : window.get(0);
        var pending = state.pendingMatching.head();
        var ingressHead = ingress.first();
        var control = commandPipeline.activeControl();
        return "window=" + (window == null ? 0 : window.size())
                + " ingress=" + ingress.size()
                + " control=" + (control == null ? "none" : control.command.header().messageType()
                        + "/" + control.command.header().commandId())
                + " head=" + (head == null ? "none" : head.sequence + "/"
                        + head.request.header().messageType() + "/" + head.request.header().commandId())
                + " pending=" + (pending == null ? "none" : pending.sequence() + "/"
                        + pending.command().header().messageType() + "/"
                        + pending.matchingLifecycleName())
                + " ingressHead=" + (ingressHead == null ? "none" : ingressHead.action != null ? "BOUNDARY" : ingressHead.command.header().messageType()
                        + "/" + ingressHead.command.header().commandId())
                + " matcherSubmissionDepth=" + state.matcherPipeline.submissionDepth()
                + " matcherCompletionDepth=" + state.matcherPipeline.completionDepth()
                + " controlLanes=" + state.runtimeState.controlLaneDiagnostics()
                + " completionAvailable=" + ownerCompletionAvailable()
                + " localMatchingWork=" + state.matchingProgress.hasLocalMatchingWork()
                + " drainWork=" + state.matchingProgress.hasDrainWork();
    }

    /** 仅框架快照边界需要等待：快照不能遗漏已经复制但尚未完成的业务。 */
    private void finishSnapshotPrefix(long deadline) {
        while (hasCommandIngress() || commandPipeline.hasActiveControl()
                || (executionMode != TradingExecutionMode.FUSED && commandPipeline.commandWindow().size() != 0)) {
            progressCommands();
            idleCommand(0, deadline);
        }
    }

    /** 已复制但尚未返回终态的命令及日志推进边界数量，用于运行积压观测。 */
    public int pendingCommandCount() {
        return executionMode == TradingExecutionMode.FUSED
                ? ingress.size()
                : ingress.size() + commandPipeline.commandWindow().size();
    }

    /**
     * Owner 在调用完整推进器前的廉价门禁。
     *
     * <p>在途窗口本身不是工作：队首等待 Matcher/Lane 时再次调用
     * {@link #pollCommands()} 只会重复探测同一个未就绪前缀。新的、尚未判定为该前缀
     * 阻塞的入站命令仍需要一次推进；完成通知或 Owner 本地可推进状态则由 Runtime
     * 提供其余唤醒来源。</p>
     */
    /** 判断 Owner 是否存在可以产生实际进展的入站命令或完成通知。 */
    boolean ownerWorkAvailable() {
        if (executionMode == TradingExecutionMode.FUSED) {
            var next = ingress.first();
            return next != null && next.action == null;
        }
        if (pendingIngressReady()) return true;
        // 已经观察到完成游标没有变化；在生产者发布新通知前，重复进入完整推进器不会产生进展。
        if (commandPipeline.waitingForMatchingNotification()) return ownerCompletionAvailable();
        return state != null && state.matchingProgress.hasDrainWork();
    }

    /**
     * 已被当前队首依赖挡住的入站命令不能把 Owner 重新拉入完整推进循环；队首完成后
     * {@link #ownerCompletionAvailable()} 或 Runtime 的本地进展门禁会再次放行。
     */
    private boolean pendingIngressReady() {
        var next = ingress.first();
        if (next == null || next.action != null) return false;
        ClusterCommandWindow window = commandPipeline.commandWindow();
        if (window.size() == window.capacity()) return false;
        return !commandPipeline.isIngressBlocked(next.command);
    }

    /** 边界任务只等待其前面的命令，不能把自身或之后的输入纳入等待条件。 */
    private boolean hasCommandIngress() {
        var next = ingress.first();
        return next != null && next.action == null;
    }

    /** 返回当前撮合命令窗口大小，供兼容测试和运行诊断使用。 */
    int commandWindowSize() {
        return executionMode == TradingExecutionMode.FUSED ? 0 : commandPipeline.commandWindow().size();
    }

    /** 返回撮合命令窗口峰值，供兼容测试和运行诊断使用。 */
    int commandWindowHighWaterMark() { return commandPipeline.commandWindowHighWaterMark(); }

    /** 返回 Owner 持有的撮合命令窗口，供同包测试检查提交边界。 */
    ClusterCommandWindow commandWindow() { return commandPipeline.commandWindow(); }

    /** 返回实时请求队列，供兼容测试和独立回放工具注入请求。 */
    ManyToOneConcurrentArrayQueue<RealtimeFrame> snapshotRequests() {
        return realtimeBoundary.requests();
    }

    /** 在异步命令等待期间让出 Owner 线程，同时执行中断和超时保护。 */
    private void idleCommand(int work, long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // 这是运行时故障，不是复制的业务拒绝，也不能转换成延迟完成。
            throw new IllegalStateException("cluster log callback completion interrupted or timed out: "
                    + stalledCommandDiagnostics());
        }
        idleStrategy.idle(work);
    }

    /** 使用默认超时时间捕获一致性快照。 */
    byte[] captureSnapshot(long snapshotId) {
        return captureSnapshot(snapshotId, snapshotDeadline());
    }

    /** 在指定截止时间内捕获一致性快照。 */
    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return captureSnapshotSections(snapshotId, deadlineNanos).toByteArray();
    }

    /** 捕获分段快照，生产 Aeron 出口直接逐段发布，避免再合并一份完整 byte[]。 */
    SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long snapshotId) {
        return captureSnapshotSections(snapshotId, snapshotDeadline());
    }

    /** 计算快照屏障截止时间。 */
    private long snapshotDeadline() {
        return Math.addExact(System.nanoTime(),
                java.util.concurrent.TimeUnit.SECONDS.toNanos(SNAPSHOT_TIMEOUT_SECONDS));
    }

    /** 先排空已复制命令，再轮询交易运行时的快照屏障。 */
    private SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long snapshotId, long deadlineNanos) {
        try {
            processingLogCallback = true;
            finishSnapshotPrefix(deadlineNanos);
            state.assertClusterCallbackComplete();
            state.snapshots.beginSnapshot(snapshotId, deadlineNanos);
            idleStrategy.reset();
            while (true) {
                SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = state.snapshots.pollSnapshotSections(
                        logContext == null ? 0 : logContext.timestamp,
                        logContext == null ? 0 : logContext.position,
                        System.nanoTime());
                if (snapshot != null) return snapshot;
                idleStrategy.idle();
            }
        } finally {
            processingLogCallback = false;
        }
    }

    /** 处理集群角色切换，清理不能跨 Leader 任期继续发送的出口状态。 */
    void roleChange(Cluster.Role newRole) {
        realtimeBoundary.roleChange(newRole);
        log.info("Aeron core role-change productLine={} role={}", productLine, newRole);
    }

    /** 推进不属于交易命令主流程的实时读取工作。 */
    int pollBackgroundWork(long nowNs) {
        if (processingLogCallback || pendingCommandCount() != 0 || !realtimeBoundary.enabled()) {
            return 0;
        }
        return progressRealtimeReads(nowNs);
    }

    /** 从实时请求队列取出一个请求，并在已提交位置上生成快照或盘口。 */
    private int progressRealtimeReads(long nowNs) {
        return realtimeBoundary.poll(state, logContext.timestamp, nowNs);
    }

    /** 输出命令流水线诊断并释放 Owner、实时出口和交易运行时资源。 */
    void terminate() {
        log.info("Aeron core command-window productLine={} {}",
                productLine, commandPipeline.terminationSummary());
        this.logContext = null;
        realtimeBoundary.close();
        try {
            if (state != null) state.close();
        } finally {
            // Borrowed decoded input must outlive the Matcher/Lane workers during shutdown.
            commandPipeline.clearCommandContainers();
            if (fusedDecoder != null) fusedDecoder.clearCommandSlot();
            ingress.discard();
            state = null;
        }
    }

    /** 会话打开后尝试推进之前积压的命令。 */
    void sessionOpened() {
        processingLogCallback = true;
        try {
            progressCommands();
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally { processingLogCallback = false; }
    }

    /** 会话关闭后继续推进交易状态；未发送响应由集群出口清理。 */
    void sessionClosed(long sessionId) {
        drainFromLogEvent();
    }

    /** 从集群回调边界触发一次 Owner 命令推进。 */
    private void drainFromLogEvent() { pollCommands(); }

    /** 只能由持有交易状态的 Owner 线程调用；不属于 Aeron 的背景回调。 */
    void ownerCompletionSignal(Runnable signal) {
        state.runtimeState.ownerCompletionSignal(signal);
        state.matcherPipeline.completionSignal(signal);
    }

    /** 启动尚未收到首条日志时 journal 未激活；休眠探测只能读完成队列，不能执行运行期健康门禁。 */
    boolean ownerCompletionAvailable() {
        return state != null && (state.runtimeState.hasMatchingNotifications()
                || state.matcherPipeline.hasMatchingCompletions()
                || state.hasCompletedLaneCommit());
    }

    /** 在 Owner 线程上收集完成通知、提交命令并推进实时读取。 */
    int pollCommands() {
        long before = commandPipeline.commandProgress();
        long matchingBefore = state.pendingMatching.dispatchRevision();
        processingLogCallback = true;
        try {
            progressCommands();
            int realtimeWork = pendingCommandCount() == 0 && realtimeBoundary.enabled()
                    ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != commandPipeline.commandProgress()
                    || matchingBefore != state.pendingMatching.dispatchRevision() ? 1 : 0)
                    + realtimeWork;
        } catch (org.agrona.concurrent.AgentTerminationException fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            if (executionMode != TradingExecutionMode.FUSED) {
                retainPendingPreparation();
            }
            processingLogCallback = false;
        }
    }

    /** 角色切换和快照等控制边界先完成其前面的已复制命令。 */
    void finishCommands() { finishSnapshotPrefix(snapshotDeadline()); }

    /** 返回当前产品线的权威交易运行时。 */
    TradingCoreRuntime state() {
        if (state == null) throw new IllegalStateException("clustered service is not started");
        return state;
    }

    TradingCoreRuntime currentStateOrNull() {
        return state;
    }

    /** 绑定外部创建的实时输出，供兼容测试和独立回放工具使用。 */
    void attachRealtime(RealtimeOutbox outbox,
                        RealtimeStateCapture capture) {
        realtimeBoundary.attach(outbox, capture, state);
    }

    /** 释放已经交给网络出口或因会话关闭而丢弃的响应对象。 */
    void releaseResponse(CoreResponse response) {
        if (state != null) state.releaseResponse(response);
    }

    /** 从 Aeron 快照片段恢复交易运行时。 */
    void loadSnapshot(SnapshotFragmentSource snapshotSource, BooleanSupplier endOfStream) {
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        while (!endOfStream.getAsBoolean()) {
            int fragments = snapshotSource.poll(
                    (buffer, offset, length, header) -> recovery.accept(buffer, offset, length), 10);
            idleStrategy.idle(fragments);
        }
        replaceState(recovery.decode(productLine));
    }

    /** 在累积 Aeron 快照片段前校验共享的快照大小上限。 */
    public static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        if (currentLength < 0 || fragmentLength < 0
                || currentLength > SectionedCoreSnapshotCodec.MAX_SNAPSHOT_BYTES - fragmentLength) {
            throw new IllegalStateException("Aeron core snapshot exceeds maximum size");
        }
    }

    /** 从完整快照字节恢复交易运行时。 */
    void restoreSnapshot(byte[] snapshot) {
        replaceState(TradingCoreRuntime.fromSnapshot(productLine, snapshot));
    }

    /** 恢复已按 Aeron fragment 接收和校验的分段快照，不再物化完整输入副本。 */
    void restoreSnapshot(SectionedCoreSnapshotCodec.RecoveryBuffer recovery) {
        if (recovery == null) throw new IllegalArgumentException("snapshot recovery is null");
        replaceState(recovery.decode(productLine));
    }

    /** 替换交易运行时，并重新绑定实时捕获器。 */
    private void replaceState(TradingCoreRuntime restored) {
        state.close();
        state = restored;
        realtimeBoundary.rebindState(state);
    }

    /** 提供快照片段读取能力，避免 Owner 依赖具体的 Aeron Image 类型。 */
    @FunctionalInterface
    interface SnapshotFragmentSource {
        /** 读取最多指定数量的快照片段，并把片段交给回调。 */
        int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    /** 将已经提交的命令响应交给响应出口，不在交易 Owner 中编码网络协议。 */
    private void offerResponse(long sessionId, CoreMessage request, CoreResponse response) {
        publishResponse(responseSink, sessionId, request, response, state.committedCoreSequence());
    }

    /** 发布响应辅助方法，供内部及测试/基准环境直接调用。 */
    static void publishResponse(ResponseSink sink, long sessionId, CoreMessage request,
                                CoreResponse response, long committedSequence) {
        CoreMessageHeader header = request.responseHeader(responseType(request.header()));
        sink.offer(sessionId, header, response, committedSequence);
    }

    /** 按请求类型计算协议层的响应消息类型。 */
    static CoreMessageType responseType(CoreMessageHeader requestHeader) {
        return switch (requestHeader.messageType()) {
            case USER_STATE_QUERY -> CoreMessageType.USER_STATE_RESULT;
            case ORDER_STATE_QUERY, CLIENT_ORDER_STATE_QUERY -> CoreMessageType.ORDER_STATE_RESULT;
            case BOOK_STATE_QUERY -> CoreMessageType.BOOK_STATE_RESULT;
            case ORDER_BOOK_BOOTSTRAP_QUERY -> CoreMessageType.ORDER_BOOK_BOOTSTRAP_RESULT;
            case LIQUIDATION_WORK_QUERY -> CoreMessageType.LIQUIDATION_WORK_RESULT;
            case USER_OPEN_ORDERS_QUERY -> CoreMessageType.USER_OPEN_ORDERS_RESULT;
            case TRIGGER_ORDER_QUERY -> CoreMessageType.TRIGGER_ORDER_RESULT;
            case USER_OPEN_TRIGGER_ORDERS_QUERY -> CoreMessageType.USER_OPEN_TRIGGER_ORDERS_RESULT;
            case FUNDING_PROGRESS_QUERY -> CoreMessageType.FUNDING_PROGRESS_RESULT;
            case SETTLEMENT_PROGRESS_QUERY -> CoreMessageType.SETTLEMENT_PROGRESS_RESULT;
            case COMMAND_RESULT_QUERY -> CoreMessageType.COMMAND_RESULT_RESULT;
            case RISK_SCAN_CONTROL_QUERY -> CoreMessageType.RISK_SCAN_CONTROL_RESULT;
            case INSTRUMENT_MAINTENANCE_QUERY -> CoreMessageType.INSTRUMENT_MAINTENANCE_RESULT;
            default -> requestHeader.kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
