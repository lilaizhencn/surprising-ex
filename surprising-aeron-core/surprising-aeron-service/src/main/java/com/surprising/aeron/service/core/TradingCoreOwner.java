package com.surprising.aeron.service.core;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.client.RealtimeOutbox;
import com.surprising.aeron.config.CoreConfiguration;
import com.surprising.aeron.config.CoreRuntimeConfiguration;
import com.surprising.aeron.config.RealtimeConfiguration;
import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.OwnerLogContext;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.order.OrderCommandQueue.IngressWindow;
import com.surprising.aeron.service.order.OrderCommandQueue.PipelineState;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/** 启动步骤 8、交易步骤 1 和 5：在 Owner 线程推进复制命令，并把完成结果交给响应出口。公开的恢复、快照、角色切换和完成通知装配方法供启动层在 Owner 线程调用，不能据此跨线程访问状态。 */
@Slf4j
public final class TradingCoreOwner {

    /** Owner 独占的命令阶段、依赖等待、控制槽和有序提交状态。 */
    private final OrderCommandQueue.PipelineState commandPipeline;
    /** Cluster 到 Owner 的唯一单生产者、单消费者输入环。返回 Cluster 与 Owner 共用的原输入环。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OwnerIngressRing ingress = new OwnerIngressRing();
    private final CoreRuntimeConfiguration configuration;

    private final ResponseSink responseSink;
    private final TradingRealtimeBoundary realtimeBoundary;

    /** 交易终态的出口契约，隔离 Owner 的业务推进与 Cluster 回调线程的网络发送。 */
    public @FunctionalInterface
    interface ResponseSink {
        /** 接收已完成响应、目标会话及提交序号。异步出口保留响应头租约至编码结束。 */
        void offer(long sessionId, CoreMessageHeader header, CoreResponse response, long committedSequence);
    }

    /** 本 Owner 唯一产品线，决定账户、规则、Matcher 和恢复隔离。 */
    private final ProductLine productLine;
    /** 恢复快照后由恢复运行时携带自己的拓扑。 */
    private final LaneTopology startupTopology;
    /** 当前权威交易运行时，由 Owner 线程访问，未启动或退出后为 null。 */
    private TradingCoreRuntime state;
    private OwnerLogContext logContext;

    private final TradingCoreRuntime.MatchingCommitHandler matchingCommitHandler;

    private static final int DEADLINE_CHECK_MASK = 0x3FF;
    /** 仅 Owner 自增的分频计数器，线程中断检查不受此分频影响。 */
    private int deadlineCheckCounter;

    /** 校验产品线和响应出口，并准备命令流水线。此时不启动交易工作线程。 */
    public TradingCoreOwner(ProductLine productLine, ResponseSink responseSink) {
        this(productLine, responseSink, CoreConfiguration.standaloneLaneTopology(),
                CoreRuntimeConfiguration.standalone(), RealtimeConfiguration.standalone());
    }

    public TradingCoreOwner(ProductLine productLine, ResponseSink responseSink, LaneTopology startupTopology,
            CoreRuntimeConfiguration configuration, RealtimeConfiguration realtimeConfiguration) {
        this.configuration = java.util.Objects.requireNonNull(configuration);
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
        this.startupTopology = java.util.Objects.requireNonNull(startupTopology, "startupTopology");
        this.responseSink = java.util.Objects.requireNonNull(responseSink, "response sink");
        this.realtimeBoundary = new TradingRealtimeBoundary(productLine, realtimeConfiguration);
        commandPipeline = new OrderCommandQueue.PipelineState(configuration.ownerCommandWindow());
        matchingCommitHandler = commandPipeline::completeControlMatching;
    }

    /** 启动步骤 8：接入复制日志上下文。 */
    public void start(OwnerLogContext logContext) {
        this.logContext = logContext;
        if (state == null) state = new TradingCoreRuntime(productLine, startupTopology, configuration);
        commandPipeline.reset();
        log.info("Aeron core role productLine={} role={}", productLine, logContext.role());
        realtimeBoundary.start(logContext.role(), logContext.position(), state);
    }

    /** 没有业务队首时释放此前缓存，避免拿上一条输入解释下一条命令。 */
    private void retainPendingPreparation() {
        var next = ingress.first();
        commandPipeline.retainPendingPreparation(next == null ? null : next.command());
    }

    /** 独立撮合命令把输入租约交给窗口，按原日志顺序提交。 */
    private void progressCommands() {
        state.bindOwner();
        OrderCommandQueue.IngressWindow window = commandPipeline.commandWindow();

        if (!commandPipeline.hasCommittingHead() && window.size() != 0) {
            beginCommandCommit();
        }
        if (commandPipeline.hasCommittingHead()) {
            pollCommandCommitBatch(true, OrderCommandQueue.PipelineState.COMPLETION_BATCH_SIZE);
        }
        if (commandPipeline.hasActiveControl()) {
            if (!pollControl()) return;
        }

        int admittedInputs = 0;
        while (admittedInputs < configuration.ownerInputBatchSize() && window.size() < window.capacity() && !commandPipeline.hasActiveControl()) {
            var next = ingress.first();
            if (next == null) break;
            if (commandPipeline.isIngressBlocked(next.command())) break;
            commandPipeline.clearBlockedIngress();
            logContext.timestamp(next.timestamp());
            logContext.position(next.position());
            if (!next.ownerObserved()) {
                next.ownerObserved(true);
            }
            boolean eligible = state.prepareClusterPipelineScope(next.command(), window);
            boolean retry = eligible && !state.pendingMatching().isEmpty()
                    && state.matchingSequence(next.command().header().commandIdMsb(), next.command().header().commandIdLsb()) != 0;
            int prefix = !eligible || retry ? window.size() : window.conflictingPrefixSize();
            if (prefix != 0 && window.size() != 0) {
                if (!commandPipeline.hasCommittingHead()) {
                    beginCommandCommit();
                }
                if (pollCommandCommitBatch(true, OrderCommandQueue.PipelineState.COMPLETION_BATCH_SIZE) > 0) {
                    retry = eligible && !state.pendingMatching().isEmpty()
                            && state.matchingSequence(next.command().header().commandIdMsb(), next.command().header().commandIdLsb()) != 0;
                    prefix = !eligible || retry ? window.size() : window.conflictingPrefixSize();
                }
            }
            if (prefix != 0) {
                commandPipeline.rememberBlockedIngress(next.command(), prefix);
                if (!commandPipeline.hasCommittingHead() && window.size() != 0) {
                    beginCommandCommit();
                }
                break;
            }
            if (!eligible) {
                if (state.requiresOwnerLaneAccessForPreparation(next.command())
                        && !state.runtimeState.tryAcquireOwnerLaneAccess()) break;
                commandPipeline.beginControl(next);
                state.assertClusterCallbackComplete();
                beginCapture(next.position(), next.timestamp());
                commandPipeline.startProgressDeadline();
                commandPipeline.controlResponse(state.applyDecodedCommand(next.command(), next.timestamp(), next.position(),
                        window.decodedIfPresent(next.command()), false, next.fingerprint()));
                commandPipeline.recordControlProgress();
                boolean matching = TradingCoreRuntime.isMatchingCommand(next.command().header().messageType());
                commandPipeline.rememberMatchingResponseSequence(matching ? state.matchingSequence(
                        next.command().header().commandIdMsb(), next.command().header().commandIdLsb()) : 0L);
                pollControl();
                break;
            }
            if (window.size() == 0) state.assertClusterCallbackComplete();
            var entry = window.add(next.sessionId(), next.command(), next.timestamp(), next.position());
            var fingerprint = next.fingerprint();
            ingress.remove();
            CoreResponse result = state.applyDecodedCommand(entry.request, entry.timestamp, entry.position,
                    window.decoded(entry.request), true, fingerprint);
            long sequence = result != null && result.resultCode() == com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING
                    ? result.committedCoreSequence() : 0;
            window.bindSequence(entry, sequence);
            entry.response = sequence == 0 ? result : null;
            admittedInputs++;
            commandPipeline.recordAdmission();
        }

        if (!commandPipeline.hasCommittingHead() && window.size() != 0) {
            beginCommandCommit();
        }
    }

    private void beginCapture(long position, long timestamp) {
        if (realtimeBoundary.enabled()) {
            realtimeBoundary.beginCapture(state, position, timestamp);
        }
    }

    /** 把窗口当前提交头登记为提交阶段，并恢复该命令的实时捕获元数据及等待截止时间。 */
    private void beginCommandCommit() {
        commandPipeline.beginHeadCommit();
        var last = commandPipeline.committingHead();
        beginCapture(last.position, last.timestamp);
        commandPipeline.startProgressDeadline();
    }

    /** 可提前派发财务后缀，但终态仍按队首顺序交出。 */
    private int pollCommandCommitBatch(boolean advanceMatching, int maxBatch) {
        int committedCount = 0;
        OrderCommandQueue.IngressWindow window = commandPipeline.commandWindow();
        while (committedCount < maxBatch) {
            if (!commandPipeline.hasCommittingHead()) {
                if (window.size() == 0) break;
                beginCommandCommit();
            }
            var last = commandPipeline.committingHead();
            long dispatchThrough = Math.max(last.sequence, window.lastMatchingSequence());
            boolean adv = advanceMatching || committedCount == 0;
            if (!adv && last.sequence != 0) {
                adv = !state.commits.matchingCommitReady(state.pendingMatching().get(last.sequence));
            }
            if (last.sequence != 0) {
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

    /** 条件齐备后提交实时帧及响应，释放账户访问权、输入和解码租约。 */
    private boolean pollControl() {
        OwnerIngressRing.Entry activeControl = commandPipeline.activeControl();
        var request = activeControl.command();
        if (state.directCommand.active()) {
            commandPipeline.controlResponse(state.directCommand.poll());
            if (commandPipeline.controlResponse() == null) { checkProgressDeadline(); return false; }
        }
        state.commits.commitReadyMatching(OrderCommandQueue.PipelineState.COMPLETION_BATCH_SIZE,
                activeControl.timestamp(), activeControl.position(), false, matchingCommitHandler);
        if (state.pendingMatching().firstSequence() != 0) { checkProgressDeadline(); return false; }
        if (commandPipeline.hasMatchingResponseSequence()) {
            if (commandPipeline.matchingResponse() == null) {
                throw new IllegalStateException("missing terminal callback result");
            }
            commandPipeline.controlResponse(commandPipeline.matchingResponse());
        }
        if (state.bookQueries.hasPendingQueries() && state.bookQueries.hasPendingQuery(request.header().commandId())) {
            CoreResponse result = state.bookQueries.takeQueryResult(request.header().commandId());
            if (result == null) { checkProgressDeadline(); return false; }
            commandPipeline.controlResponse(result);
        }
        state.assertClusterCallbackComplete();
        if (realtimeBoundary.enabled()) {
            realtimeBoundary.commit(state, activeControl.position());
        }
        if (activeControl.sessionId() != NO_SESSION_ID) {
            offerResponse(activeControl.sessionId(), request, commandPipeline.controlResponse());
        }
        state.runtimeState.releaseOwnerLaneAccess();
        commandPipeline.finishControl();
        ingress.remove();
        if (commandPipeline.commandWindow().decodedIfPresent(request) != null)
            commandPipeline.commandWindow().releaseDecoded();
        request.releaseDecoderSlot();
        commandPipeline.recordControlProgress();
        return true;
    }

    /** 每次检查线程中断，每 1024 次检查一次命令截止时间。 */
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

    /** 拼出当前窗口、控制命令、Matcher、账户与完成队列的诊断信息，供卡住或超时定位。它是运行观测，不是可恢复的业务快照。 */
    String stalledCommandDiagnostics() {
        var window = commandPipeline.commandWindow();
        var head = window == null || window.size() == 0 ? null : window.get(0);
        var pending = state.pendingMatching().head();
        var ingressHead = ingress.first();
        var control = commandPipeline.activeControl();
        return "window=" + (window == null ? 0 : window.size())
                + " ingress=" + ingress.size()
                + " control=" + (control == null ? "none" : control.command().header().messageType()
                        + "/" + control.command().header().commandId())
                + " head=" + (head == null ? "none" : head.sequence + "/"
                        + head.request.header().messageType() + "/" + head.request.header().commandId())
                + " pending=" + (pending == null ? "none" : pending.sequence() + "/"
                        + pending.command().header().messageType() + "/"
                        + pending.matchingLifecycleName())
                + " ingressHead=" + (ingressHead == null || ingressHead.command() == null ? "none" : ingressHead.command().header().messageType()
                        + "/" + ingressHead.command().header().commandId())
                + " matcherSubmissionDepth=" + state.matcherPipeline().submissionDepth()
                + " matcherCompletionDepth=" + state.matcherPipeline().completionDepth()
                + " controlLanes=" + state.runtimeState.controlLaneDiagnostics()
                + " completionAvailable=" + ownerCompletionAvailable()
                + " localMatchingWork=" + state.matchingProgress.hasLocalMatchingWork()
                + " drainWork=" + state.matchingProgress.hasDrainWork();
    }

    private void finishSnapshotPrefix(long deadline) {
        while (hasCommandIngress() || commandPipeline.hasActiveControl()
                || commandPipeline.commandWindow().size() != 0) {
            progressCommands();
            idleCommand(deadline);
        }
    }

    /** 返回输入环和流水窗口中的待处理条目数。 */
    public int pendingCommandCount() {
        return ingress.size() + commandPipeline.commandWindow().size();
    }

    private boolean hasCommandIngress() {
        return ingress.first() != null;
    }

    /** 返回流水窗口当前条数。 */
    int commandWindowSize() {
        return commandPipeline.commandWindow().size();
    }

    /** 返回命令流水线记录的窗口积压高水位，用于观测容量压力。 */
    public int commandWindowHighWaterMark() { return commandPipeline.commandWindowHighWaterMark(); }

    /** 借出命令流水线的窗口引用，供既有编排和测试查看。 */
    OrderCommandQueue.IngressWindow commandWindow() { return commandPipeline.commandWindow(); }

    private void idleCommand(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // 这是运行时故障，不是复制的业务拒绝，也不能转换成延迟完成。
            throw new IllegalStateException("cluster log callback completion interrupted or timed out: "
                    + stalledCommandDiagnostics());
        }
        Thread.onSpinWait();
    }

    /** 按默认快照等待期限捕获快照，并把分块结果合成一个字节数组。 */
    public byte[] captureSnapshot(long snapshotId) {
        return captureSnapshot(snapshotId, snapshotDeadline());
    }

    /** 按指定纳秒截止时间捕获分块快照，再合成完整字节数组。 */
    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return captureSnapshotSections(snapshotId, deadlineNanos).toByteArray();
    }

    /** 按默认快照等待期限返回分块结果，避免必须先合成整个快照。 */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long snapshotId) {
        return captureSnapshotSections(snapshotId, snapshotDeadline());
    }

    /** 加法溢出直接报错，本方法不额外校验配置正负范围。 */
    private long snapshotDeadline() {
        return Math.addExact(System.nanoTime(),
                java.util.concurrent.TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds()));
    }

    private SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long snapshotId, long deadlineNanos) {
        finishSnapshotPrefix(deadlineNanos);
        state.assertClusterCallbackComplete();
        state.snapshots.beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = state.snapshots.pollSnapshotSections(
                    logContext == null ? 0 : logContext.timestamp(),
                    logContext == null ? 0 : logContext.position(),
                    System.nanoTime());
            if (snapshot != null) return snapshot;
            Thread.onSpinWait();
        }
    }

    /** 更新实时边界的角色并记录日志。外层负责更新 Owner 日志上下文和先前命令的完成边界。 */
    public void roleChange(Cluster.Role newRole) {
        realtimeBoundary.roleChange(newRole);
        log.info("Aeron core role-change productLine={} role={}", productLine, newRole);
    }

    private int progressRealtimeReads(long nowNs) {
        return realtimeBoundary.poll(state, logContext.timestamp(), nowNs);
    }

    /** 记录摘要并关闭实时边界，然后关闭交易运行时。运行时关闭完成或抛错后清命令容器和输入租约。仍被工作线程借用的解码输入必须等运行时关闭后再释放。 */
    public void terminate() {
        log.info("Aeron core command-window productLine={} {}",
                productLine, commandPipeline.terminationSummary());
        this.logContext = null;
        realtimeBoundary.close();
        try {
            if (state != null) state.close();
        } finally {
            // 关闭时先结束 Matcher 和账户工作线程，再释放它们仍可能借用的解码输入。
            commandPipeline.clearCommandContainers();
            ingress.discard();
            state = null;
        }
    }

    /** 只探测现有 Matcher 通知、撮合完成和 Lane 回执。 */
    boolean ownerCompletionAvailable() {
        return state != null && (state.runtimeState.hasMatchingNotifications()
                || state.matcherPipeline().hasMatchingCompletions()
                || state.hasCompletedLaneCommit());
    }

    /** 单轮推进入口：比较命令进度和派发修订，空闲时兼顾实时读取。返回是否有进展及实时工作量，不能当作本轮完成命令数。 */
    public int pollCommands() {
        long before = commandPipeline.commandProgress();
        long matchingBefore = state.pendingMatching().dispatchRevision();
        try {
            progressCommands();
            int realtimeWork = pendingCommandCount() == 0 && realtimeBoundary.enabled()
                    ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != commandPipeline.commandProgress()
                    || matchingBefore != state.pendingMatching().dispatchRevision() ? 1 : 0)
                    + realtimeWork;
        } catch (org.agrona.concurrent.AgentTerminationException fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            retainPendingPreparation();
        }
    }

    /** 使用默认快照期限完成当前动作边界之前的命令，供角色切换、快照及退出边界复用。 */
    public void finishCommands() { finishSnapshotPrefix(snapshotDeadline()); }

    /** 取得已启动运行时。引用仍归 Owner，不提供跨线程账户读取权限。 */
    public TradingCoreRuntime state() {
        if (state == null) throw new IllegalStateException("clustered service is not started");
        return state;
    }

    /** 把已有实时 outbox 及可空变更捕获器交给实时边界，不另建交易状态。 */
    public void attachRealtime(RealtimeOutbox outbox,
                        TradingRealtimeBoundary.StateCapture capture) {
        realtimeBoundary.attach(outbox, capture, state);
    }

    /** 运行时仍在时归还响应缓冲租约。 */
    public void releaseResponse(CoreResponse response) {
        if (state != null) state.releaseResponse(response);
    }

    /** 从完整快照字节恢复指定产品线的候选运行时，再通过统一替换入口接入。 */
    void restoreSnapshot(byte[] snapshot) {
        replaceState(SectionedCoreSnapshotCodec.decodePassive(snapshot, productLine, configuration, startupTopology));
    }

    /** 要求恢复缓冲非空，解码候选运行时后交给统一替换入口。 */
    public void restoreSnapshot(com.surprising.aeron.service.snapshot.SectionedCoreSnapshotRecovery recovery) {
        if (recovery == null) throw new IllegalArgumentException("snapshot recovery is null");
        replaceState(recovery.decodePassive(productLine, configuration, startupTopology));
    }

    /** 先关闭旧运行时，再接入恢复结果并重新绑定实时状态。 */
    private void replaceState(TradingCoreRuntime restored) {
        state.close();
        state = restored;
        realtimeBoundary.rebindState(state);
    }

    private void offerResponse(long sessionId, CoreMessage request, CoreResponse response) {
        publishResponse(responseSink, sessionId, request, response, state.committedCoreSequence());
    }

    /** 从请求借出对应类型的响应头并交给出口。异步出口要保留头部和响应租约直到编码完成，本方法不做网络发送。 */
    public static void publishResponse(ResponseSink sink, long sessionId, CoreMessage request,
                                CoreResponse response, long committedSequence) {
        CoreMessageHeader header = request.responseHeader(responseType(request.header()));
        sink.offer(sessionId, header, response, committedSequence);
    }

    /** 把查询请求映射到对应查询结果类型，其余查询使用状态哈希结果类型，普通命令使用 COMMAND_RESULT。 */
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
