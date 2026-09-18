package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.service.state.realtime.RealtimeStateCapture;
import com.surprising.aeron.service.orchestration.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.orchestration.snapshot.CoreStateSnapshotCodec;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 交易 Owner：按确定性边界推进交易，未完成命令与响应跨推进轮次保留。
 *
 * <p>该类不实现 {@link ClusteredService}。Aeron 回调、会话和网络出口由
 * {@link com.surprising.aeron.service.cluster.AeronTradingClusterService} 持有；Owner 只接收已复制的不可变命令、
 * 推进交易状态并产生终态交接。</p>
 */
public final class TradingCoreOwner {

    /** 交易 Owner 的运行日志；仅记录生命周期和诊断信息，不进入交易状态计算。 */
    private static final Logger log = LoggerFactory.getLogger(TradingCoreOwner.class);

    /** 快照阶段的最长等待时间；交易命令本身使用命令流水线的截止时间。 */
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;

    /** 复制日志命令的执行阶段、依赖等待和提交边界的唯一状态持有者。 */
    private final OwnerCommandPipelineState commandPipeline = new OwnerCommandPipelineState();
    /** 已提交响应的交接边界，不参与业务状态计算。 */
    private final OwnerResponsePublisher responsePublisher;
    /** 实时盘口和快照的独立传输边界，不阻塞撮合主流程。 */
    private final TradingRealtimeBoundary realtimeBoundary;

    /** 兼容旧的 ClusteredService 测试和独立回放工具的响应交接函数。 */
    @FunctionalInterface
    interface ResponseSink {
        /** 接收已经提交的不可变响应，由外部出口负责网络线程交接。 */
        void offer(ClientSession session, CoreMessageHeader header, CoreResponse response, long committedSequence);
    }

    /** 所属产品线；它决定命令、账户、撮合器和快照的隔离边界。 */
    private final ProductLine productLine;
    /** 当前产品线的权威交易运行时；Owner 只在自己的线程访问它。 */
    private TradingCoreRuntime state;
    /** Aeron 集群对象，Owner 只用于时间、角色和日志位置边界。 */
    private Cluster cluster;
    /** 集群等待策略，所有者是当前 Owner 线程。 */
    private IdleStrategy idleStrategy;
    /** Aeron 回调可能重入背景工作，使用该标记保护交易推进边界。 */
    private boolean processingLogCallback;

    /** Matcher 完成通知使用的统一回调，最终结果回到命令流水线状态。 */
    private final TradingCoreRuntime.MatchingCommitHandler matchingCommitHandler = this::completeMatching;
    /** 快照屏障尚未就绪的诊断次数，不参与业务判断。 */
    private long snapshotFenceNotReadyCount;
    /** 快照屏障超时的诊断次数，不参与业务判断。 */
    private long snapshotFenceTimeoutCount;

    /** 创建指定产品线的 Owner。 */
    public TradingCoreOwner(ProductLine productLine) { this(productLine, null); }

    TradingCoreOwner(ProductLine productLine, ResponseSink responseSink) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
        this.responsePublisher = new OwnerResponsePublisher(responseSink);
        this.realtimeBoundary = new TradingRealtimeBoundary(productLine);
    }

    /**
     * 启动 Owner 的集群边界和交易运行时。
     *
     * <p>这里只初始化运行时资源，不执行任何业务命令；业务命令必须来自已复制的 Cluster 日志。</p>
     */
    void start(Cluster cluster) {
        this.cluster = cluster;
        if (state == null) state = new TradingCoreRuntime(productLine);
        processingLogCallback = false;
        commandPipeline.reset();
        responsePublisher.clear();
        snapshotFenceNotReadyCount = 0;
        snapshotFenceTimeoutCount = 0;
        idleStrategy = cluster.idleStrategy();
        log.info("Aeron core role productLine={} role={}", productLine, cluster.role());
        realtimeBoundary.start(cluster, state);
    }

    /** 接收一条已复制命令，并在当前日志回调内推进命令流水线。 */
    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position) {
        acceptCommittedCommand(session, request, timestamp, position, null);
    }

    /** 指纹来自服务内部解码后的日志记录，不能接受客户端提供的摘要。 */
    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint) {
        acceptCommittedCommand(session, request, timestamp, position, fingerprint, true);
    }

    /** 独立 Owner 连续收取入口后统一推进；容量不足仍立即推进，日志上下文逐条保留。 */
    void enqueueCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                 CommandFingerprint fingerprint) {
        acceptCommittedCommand(session, request, timestamp, position, fingerprint, false);
    }

    /** 统一处理两种入口，确保响应轮询、入队、推进和异常转换使用同一顺序。 */
    private void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                        CommandFingerprint fingerprint, boolean advance) {
        try {
            processingLogCallback = true;
            responsePublisher.poll(System.nanoTime(), OwnerCommandPipelineState.COMPLETION_BATCH_SIZE);
            processIngress(session, request, timestamp, position, fingerprint, advance);
        } catch (org.agrona.concurrent.AgentTerminationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // AgentRunner 否则可能只记录普通异常并继续消费后续日志消息。
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            retainPendingPreparation();
            processingLogCallback = false;
        }
    }

    /** 保留当前队首命令的解码准备状态，避免等待异步完成时重复解码。 */
    private void retainPendingPreparation() {
        commandPipeline.retainPendingPreparation();
    }

    /** 把一条已复制命令放入 Owner 队列，并按入口模式决定是否立即推进。 */
    private void processIngress(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint, boolean advance) {
        awaitIngressCapacity(request);
        commandPipeline.pendingIngress().add(session, request, timestamp, position, fingerprint);
        if (advance) progressCommands();
    }

    /** 队列满时暂停日志消费，在原日志上下文内推进已复制命令；不改变业务顺序或拒绝结果。 */
    private void awaitIngressCapacity(CoreMessage request) {
        if (commandPipeline.pendingIngress().hasCapacity(request)) return;
        long deadline = System.nanoTime() + OwnerCommandPipelineState.COMMAND_TIMEOUT_NANOS;
        do {
            progressCommands();
            if (commandPipeline.pendingIngress().hasCapacity(request)) return;
            idleCommand(0, deadline);
        } while (true);
    }

    /** 在 Owner 线程中绑定运行时并推进一轮命令状态。 */
    private void progressCommands() {
        state.bindOwner();
        state.runtimeState.enterAsynchronousCommandScope();
        try { progressCommandsInScope(); }
        finally { state.runtimeState.exitAsynchronousCommandScope(); }
    }

    /** 按“队首提交、异步控制、命令准入”的业务顺序执行一轮流水线。 */
    private void progressCommandsInScope() {
        boolean awaitingCompletion = false;
        // 每轮先推进异步工作；随后连续 ready 的队首直接退休，不重复收集整个窗口。
        boolean advanceMatching = true;
        ClusterCommandWindow window = commandPipeline.commandWindow();
        var turn = state.matchingPhaseMetrics.sampleOwnerTurn(window.size());
        try {
            for (int work = 0; work < OwnerCommandPipelineState.COMPLETION_BATCH_SIZE; work++) {
                // 当前命令的提交边界不变；未完成时仍可准入与在途命令无依赖的后续订单。
                if (!commandPipeline.hasCommittingHead() && window.size() != 0) beginCommandCommit();
                // 本轮未收到完成时仍准入独立命令；不为每个新准入项重跑整套完成收集。
                if (commandPipeline.hasCommittingHead() && !awaitingCompletion) {
                    // 先提交已经就绪的结果；本轮预算限制单次 Owner 调度时间。
                    if (pollCommandCommit(advanceMatching)) {
                        if (turn != null) turn.retired++;
                        advanceMatching = false;
                        continue;
                    }
                    awaitingCompletion = true;
                    if (turn != null) turn.headWait = true;
                }
                if (window.size() == window.capacity()) return;
                if (commandPipeline.hasActiveControl()) {
                    if (!pollControl()) return;
                    continue;
                }
                var next = commandPipeline.pendingIngress().first();
                if (next == null) return;
                if (commandPipeline.isIngressBlocked(next.command)) return;
                commandPipeline.clearBlockedIngress();
                boolean eligible = state.prepareClusterPipelineScope(next.command, window);
                boolean retry = eligible && state.matchingSequence(next.command.header().commandId()) != 0;
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
                    CoreMatchingPhaseMetrics.recordBoundary("ingressToControl", next.command.header(), next.queuedNanos);
                    commandPipeline.beginControl(next);
                    state.assertClusterCallbackComplete();
                    beginCapture(next.position, next.timestamp);
                    commandPipeline.startProgressDeadline();
                    commandPipeline.controlResponse(state.applyDecodedCommand(next.command, next.timestamp, next.position,
                            window.decodedIfPresent(next.command), false, next.fingerprint));
                    commandPipeline.recordControlProgress();
                    commandPipeline.rememberMatchingResponseSequence(
                            state.matchingSequence(next.command.header().commandId()));
                    continue;
                }
                if (window.size() == 0) state.assertClusterCallbackComplete();
                CoreMatchingPhaseMetrics.recordBoundary("ingressToAdmission", next.command.header(), next.queuedNanos);
                long admissionStart = CoreMatchingPhaseMetrics.sampleStart(next.command.header());
                var entry = window.add(next.session, next.command, next.timestamp, next.position);
                CoreResponse result = state.applyDecodedCommand(next.command, next.timestamp, next.position,
                        window.decoded(next.command), true, next.fingerprint);
                window.bindSequence(entry, state.matchingSequence(next.command.header().commandId()));
                if (entry.sequence != 0) {
                    var pending = state.pendingMatching(entry.sequence);
                    pending.establishCommitFence(next.timestamp, next.position);
                    state.pendingMatching.partitionDependenciesChanged();
                }
                CoreMatchingPhaseMetrics.recordBoundary("admissionExecution", next.command.header(), admissionStart);
                entry.response = entry.sequence == 0 ? result : null;
                commandPipeline.pendingIngress().remove();
                commandPipeline.recordAdmission();
                if (turn != null) turn.admitted++;
                advanceMatching = true;
            }
            if (turn != null) turn.budgetExhausted = true;
        } finally {
            if (turn != null) {
                turn.windowAtEnd = window.size();
                turn.end();
                turn.commit();
            }
        }
    }

    /** 在命令开始改变业务状态前建立实时事件捕获边界。 */
    private void beginCapture(long position, long timestamp) {
        realtimeBoundary.beginCapture(state, position, timestamp);
    }

    /** 每条命令有固定提交边界，不能让各副本的线程完成速度改变推送分组。 */
    private void beginCommandCommit() {
        commandPipeline.beginHeadCommit();
        var last = commandPipeline.committingHead();
        beginCapture(last.position, last.timestamp);
        commandPipeline.startProgressDeadline();
    }

    /** 公共推进按需执行；每条队首仍独立完成捕获、发布、响应和退休。 */
    private boolean pollCommandCommit(boolean advanceMatching) {
        ClusterCommandWindow window = commandPipeline.commandWindow();
        var last = commandPipeline.committingHead();
        long dispatchThrough = Math.max(last.sequence, window.lastMatchingSequence());
        long progressBefore = state.matchingProgressSequence();
        if (commandPipeline.waitingForMatchingNotification()
                && commandPipeline.canSkipMatchingPoll(dispatchThrough, progressBefore,
                state.hasMatchingNotifications())) {
            checkProgressDeadline();
            return false;
        }
        commandPipeline.beginMatchingPoll();
        // 保存不可变 header；退休会复用窗口槽位，不能随后再从 last 取命令。
        var timingHeader = last.request.header();
        long commitStart = CoreMatchingPhaseMetrics.sampleStart(timingHeader);
        // 新队首可能需要激活批量、派发结算或收取队列通知；不能因本轮已收集过而饿死续接。
        if (!advanceMatching && last.sequence != 0) {
            advanceMatching = !state.commits.matchingCommitReady(state.pendingMatching(last.sequence));
        }
        state.commits.commitReadyMatching(1,
                last.timestamp, last.position, false, last.sequence,
                dispatchThrough, advanceMatching, matchingCommitHandler);
        if (state.firstPendingMatchingSequence() != 0
                && state.firstPendingMatchingSequence() <= last.sequence) {
            commandPipeline.rememberMatchingWait(dispatchThrough, state.matchingProgressSequence(),
                    progressBefore, state.hasLocalMatchingWork());
            CoreMatchingPhaseMetrics.recordBoundary("ownerCommitAttemptWaiting", timingHeader, commitStart);
            checkProgressDeadline();
            return false;
        }
        CoreMatchingPhaseMetrics.recordBoundary("ownerCommitAttemptTerminal", timingHeader, commitStart);
        long publicationStart = CoreMatchingPhaseMetrics.sampleStart(timingHeader);
        if (window.size() == 1) state.assertClusterCallbackComplete();
        realtimeBoundary.commit(state, last.position);
        CoreMatchingPhaseMetrics.recordBoundary("ownerRealtimePublication", timingHeader, publicationStart);
        long retirementStart = CoreMatchingPhaseMetrics.sampleStart(timingHeader);
        if (last.response == null) throw new IllegalStateException("missing pipeline terminal response");
        if (last.session != null) offerResponse(last.session, last.request, last.response);
        commandPipeline.finishHeadCommit();
        state.runtimeState.releaseCompletedSequentialLaneStage();
        if (window.size() == 0) progressRealtimeReads(System.nanoTime());
        CoreMatchingPhaseMetrics.recordBoundary("ownerResponseAndRetirement", timingHeader, retirementStart);
        return true;
    }

    /** 推进单条控制命令的异步子任务、查询结果和终态提交。 */
    private boolean pollControl() {
        PendingClusterIngress.Entry activeControl = commandPipeline.activeControl();
        var request = activeControl.command;
        if (state.hasPendingDirectCommand()) {
            commandPipeline.controlResponse(state.pollDirectCommand());
            if (commandPipeline.controlResponse() == null) { checkProgressDeadline(); return false; }
        }
        state.commits.commitReadyMatching(OwnerCommandPipelineState.COMPLETION_BATCH_SIZE,
                activeControl.timestamp, activeControl.position, false, matchingCommitHandler);
        if (state.firstPendingMatchingSequence() != 0) { checkProgressDeadline(); return false; }
        if (commandPipeline.hasMatchingResponseSequence()) {
            if (commandPipeline.matchingResponse() == null) {
                throw new IllegalStateException("missing terminal callback result");
            }
            commandPipeline.controlResponse(commandPipeline.matchingResponse());
        }
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            CoreResponse result = state.takeQueryResult(querySequence);
            if (result == null) { checkProgressDeadline(); return false; }
            commandPipeline.controlResponse(result);
        }
        state.assertClusterCallbackComplete();
        realtimeBoundary.commit(state, activeControl.position);
        if (activeControl.session != null) {
            offerResponse(activeControl.session, request, commandPipeline.controlResponse());
        }
        state.runtimeState.releaseOwnerLaneAccess();
        commandPipeline.finishControl();
        commandPipeline.pendingIngress().remove();
        commandPipeline.recordControlProgress();
        return true;
    }

    /** 检查异步命令是否被中断或超过允许的等待时间。 */
    private void checkProgressDeadline() {
        if (Thread.currentThread().isInterrupted()
                || System.nanoTime() - commandPipeline.progressDeadline() >= 0)
            throw new IllegalStateException("asynchronous command progress interrupted or timed out");
    }

    /** 仅框架快照边界需要等待：快照不能遗漏已经复制但尚未完成的业务。 */
    private void finishSnapshotPrefix(long deadline) {
        while (commandPipeline.pendingIngress().size() != 0
                || commandPipeline.commandWindow().size() != 0
                || commandPipeline.hasActiveControl()) {
            progressCommands();
            idleCommand(0, deadline);
        }
    }

    /** 已复制但尚未返回终态的命令及日志推进边界数量，用于运行积压观测。 */
    public int pendingCommandCount() { return commandPipeline.pendingCommandCount(); }

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
        if (pendingIngressReady()) return true;
        // 已经观察到完成游标没有变化；在生产者发布新通知前，重复进入完整推进器不会产生进展。
        if (commandPipeline.waitingForMatchingNotification()) return ownerCompletionAvailable();
        return state != null && state.hasMatchingDrainWork();
    }

    /**
     * 已被当前队首依赖挡住的入站命令不能把 Owner 重新拉入完整推进循环；队首完成后
     * {@link #ownerCompletionAvailable()} 或 Runtime 的本地进展门禁会再次放行。
     */
    private boolean pendingIngressReady() {
        var next = commandPipeline.pendingIngress().first();
        if (next == null) return false;
        ClusterCommandWindow window = commandPipeline.commandWindow();
        if (window.size() == window.capacity()) return false;
        return !commandPipeline.isIngressBlocked(next.command);
    }

    /** 返回当前撮合命令窗口大小，供兼容测试和运行诊断使用。 */
    int commandWindowSize() { return commandPipeline.commandWindow().size(); }

    /** 返回撮合命令窗口峰值，供兼容测试和运行诊断使用。 */
    int commandWindowHighWaterMark() { return commandPipeline.commandWindowHighWaterMark(); }

    /** 返回 Owner 持有的撮合命令窗口，供同包测试检查提交边界。 */
    ClusterCommandWindow commandWindow() { return commandPipeline.commandWindow(); }

    /** 返回实时请求队列，供兼容测试和独立回放工具注入请求。 */
    ManyToOneConcurrentArrayQueue<RealtimeFrame> snapshotRequests() {
        return realtimeBoundary.requests();
    }

    /** 把 Matcher 的终态响应放回订单窗口或当前控制命令。 */
    private void completeMatching(long sequence, CoreResponse response) {
        commandPipeline.completeMatching(sequence, response);
    }

    /** 在异步命令等待期间让出 Owner 线程，同时执行中断和超时保护。 */
    private void idleCommand(int work, long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // 这是运行时故障，不是复制的业务拒绝，也不能转换成延迟完成。
            throw new IllegalStateException("cluster log callback completion interrupted or timed out");
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
            state.beginSnapshot(snapshotId, deadlineNanos);
            idleStrategy.reset();
            while (true) {
                SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = state.pollSnapshotSections(
                        cluster == null ? 0 : cluster.time(),
                        cluster == null ? 0 : cluster.logPosition(),
                        System.nanoTime());
                if (snapshot != null) return snapshot;
                idleStrategy.idle();
            }
        } catch (TradingCoreRuntime.SnapshotNotReadyException notReady) {
            snapshotFenceNotReadyCount++;
            throw notReady;
        } catch (TradingCoreRuntime.SnapshotFenceTimeoutException timeout) {
            snapshotFenceTimeoutCount++;
            throw timeout;
        } finally {
            processingLogCallback = false;
        }
    }

    /** 返回快照因已有异步工作未就绪的次数。 */
    public long snapshotFenceNotReadyCount() {
        return snapshotFenceNotReadyCount;
    }

    /** 返回快照屏障超时次数。 */
    public long snapshotFenceTimeoutCount() {
        return snapshotFenceTimeoutCount;
    }

    /** 处理集群角色切换，清理不能跨 Leader 任期继续发送的出口状态。 */
    void roleChange(Cluster.Role newRole) {
        realtimeBoundary.roleChange(newRole);
        responsePublisher.clear();
        log.info("Aeron core role-change productLine={} role={}", productLine, newRole);
    }

    /** 推进不属于交易命令主流程的实时读取工作。 */
    int pollBackgroundWork(long nowNs) {
        if (processingLogCallback || commandPipeline.pendingCommandCount() != 0 || !realtimeBoundary.enabled()) {
            return 0;
        }
        return progressRealtimeReads(nowNs);
    }

    /** 从实时请求队列取出一个请求，并在已提交位置上生成快照或盘口。 */
    private int progressRealtimeReads(long nowNs) {
        return realtimeBoundary.poll(state, cluster, nowNs);
    }

    /** 输出命令流水线诊断并释放 Owner、实时出口和交易运行时资源。 */
    void terminate() {
        log.info("Aeron core command-window productLine={} {}",
                productLine, commandPipeline.terminationSummary());
        commandPipeline.clearCommandContainers();
        responsePublisher.clear();
        this.cluster = null;
        realtimeBoundary.close();
        if (state != null) {
            state.close();
            state = null;
        }
    }

    /** 会话打开后尝试推进之前积压的命令。 */
    void sessionOpened() {
        processingLogCallback = true;
        try {
            responsePublisher.poll(System.nanoTime(), OwnerCommandPipelineState.COMPLETION_BATCH_SIZE);
            progressCommands();
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally { processingLogCallback = false; }
    }

    /** 会话关闭时丢弃对应的未发送响应，并继续推进交易状态。 */
    void sessionClosed(long sessionId) {
        responsePublisher.removeSession(sessionId);
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
                || state.matcherPipeline.hasMatchingCompletions());
    }

    /** 在 Owner 线程上收集完成通知、提交命令并推进实时读取。 */
    int pollCommands() {
        long before = commandPipeline.commandProgress();
        long matchingBefore = state.matchingProgressSequence();
        processingLogCallback = true;
        try {
            int responseWork = responsePublisher.poll(System.nanoTime(),
                    OwnerCommandPipelineState.COMPLETION_BATCH_SIZE);
            progressCommands();
            int realtimeWork = pendingCommandCount() == 0 ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != commandPipeline.commandProgress()
                    || matchingBefore != state.matchingProgressSequence() ? 1 : 0)
                    + responseWork + realtimeWork;
        } catch (org.agrona.concurrent.AgentTerminationException fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            retainPendingPreparation();
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
                || currentLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - fragmentLength) {
            throw new IllegalStateException("Aeron core snapshot exceeds maximum size");
        }
    }

    /** 从完整快照字节恢复交易运行时。 */
    void restoreSnapshot(byte[] snapshot) {
        replaceState(TradingCoreRuntime.fromSnapshot(productLine, snapshot));
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
    private void offerResponse(ClientSession session, CoreMessage request, CoreResponse response) {
        responsePublisher.publish(session, request, response, state);
    }
}
