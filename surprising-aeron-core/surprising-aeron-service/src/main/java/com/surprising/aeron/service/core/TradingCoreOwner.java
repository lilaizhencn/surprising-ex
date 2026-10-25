package com.surprising.aeron.service.core;

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
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.order.ClusterCommandWindow;
import com.surprising.aeron.service.order.OwnerCommandPipelineState;
import com.surprising.aeron.service.realtime.TradingRealtimeBoundary;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.support.ingress.OwnerIngressRing;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.Cluster;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import static com.surprising.aeron.support.egress.ClusterServiceEgress.NO_SESSION_ID;

/**
 * 启动步骤 8、交易步骤 1 和 5：在 Owner 线程推进复制命令，并把完成结果交给响应出口。
 * 持有当前产品线运行时和命令生命周期，使用有界命令窗口流水推进。
 * Cluster 会话和网络发送由 controller 承担，生命周期由 controller.AeronTradingClusterService 内联驱动。
 * 公开的恢复、快照、角色切换和完成通知装配方法供启动层在 Owner 线程调用，不能据此跨线程访问状态。
 */
@Slf4j
public final class TradingCoreOwner {

    /** Owner 独占的命令阶段、依赖等待、控制槽和有序提交状态。 */
    private final OwnerCommandPipelineState commandPipeline;
    /**  Cluster 到 Owner 的唯一单生产者、单消费者输入环。 返回 Cluster 与 Owner 共用的原输入环；调用方遵守单生产者、单消费者约定。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final OwnerIngressRing ingress = new OwnerIngressRing();
    /** 每轮输入推进预算，类加载时读取并校验，默认 64 条。 */
    private final CoreRuntimeConfiguration configuration;

    /** 已提交响应的交接出口，不执行交易状态变化。 */
    private final ResponseSink responseSink;
    /** 实时帧及读取请求的传输边界，网络由独立资源处理。 */
    private final TradingRealtimeBoundary realtimeBoundary;

    /**
     * 交易终态的出口契约，隔离 Owner 的业务推进与 Cluster 回调线程的网络发送。
     */
    public @FunctionalInterface
    interface ResponseSink {
        /**
         * 接收已完成响应、目标会话及提交序号；异步出口保留响应头租约至编码结束。
         * @param sessionId 响应目标会话 ID；无会话命令使用 NO_SESSION_ID
         * @param header 本命令的请求或响应头，调用边界内保持租约有效
         * @param response 要交出或归还的终态响应及其缓冲租约
         * @param committedSequence 响应对应的已提交 Core 序号
         */
        void offer(long sessionId, CoreMessageHeader header, CoreResponse response, long committedSequence);
    }

    /** 本 Owner 唯一产品线，决定账户、规则、Matcher 和恢复隔离。 */
    private final ProductLine productLine;
    /** 首次启动的配置拓扑；恢复快照后由恢复运行时携带自己的拓扑。 */
    private final LaneTopology startupTopology;
    /** 当前权威交易运行时，由 Owner 线程访问，未启动或退出后为 null。 */
    private TradingCoreRuntime state;
    /** 外层交接的角色、复制时间及日志位置，不含网络发送能力。 */
    private OwnerLogContext logContext;

    /** 把撮合终态交回当前控制命令的固定回调。 */
    private final TradingCoreRuntime.MatchingCommitHandler matchingCommitHandler;

    /** 以 1023 掩码把命令时钟检查分频为每 1024 次一次。 */
    private static final int DEADLINE_CHECK_MASK = 0x3FF;
    /** 仅 Owner 自增的分频计数器，线程中断检查不受此分频影响。 */
    private int deadlineCheckCounter;

    /**
     * 校验产品线和响应出口，并准备命令流水线；此时不启动交易工作线程。
     * @param productLine 必须明确指定的所属产品线
     * @param responseSink 必须存在的已提交响应出口
     */
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
        commandPipeline = new OwnerCommandPipelineState(configuration.ownerCommandWindow());
        matchingCommitHandler = commandPipeline::completeControlMatching;
    }

    /**
     * 启动步骤 8：接入复制日志上下文；没有运行时则新建，清空命令阶段，接入等待策略并启动可选实时边界。工作资源由外层随后激活。
     * @param logContext Owner 可用的复制日志元数据及等待策略
     */
    public void start(OwnerLogContext logContext) {
        this.logContext = logContext;
        if (state == null) state = new TradingCoreRuntime(productLine, startupTopology, configuration);
        commandPipeline.reset();
        log.info("Aeron core role productLine={} role={}", productLine, logContext.role());
        realtimeBoundary.start(logContext.role(), logContext.position(), state);
    }

    /**
     * 只为当前业务队首保留一次解码准备；没有业务队首时释放此前缓存，避免拿上一条输入解释下一条命令。
     */
    private void retainPendingPreparation() {
        // 当前输入环借出的队首条目，取走或释放后不能继续访问
        var next = ingress.first();
        commandPipeline.retainPendingPreparation(next == null ? null : next.command());
    }

    /**
     * 绑定当前 Owner，进入异步命令作用域后推进；退出作用域后提交输入环已消费字节，不把未完成命令的租约提前释放。
     */
    private void progressCommands() {
        state.bindOwner();
        state.runtimeState.enterAsynchronousCommandScope();
        try {
            progressCommandsInScope();
        } finally {
            state.runtimeState.exitAsynchronousCommandScope();
            ingress.commitConsumedBytes();
        }
    }

    /**
     * 主流程：先收集队首完成，再按容量、冲突前缀及重试栅栏接纳输入。控制命令保留队首；独立撮合命令把输入租约交给窗口，按原日志顺序提交。
     */
    private void progressCommandsInScope() {
        // 当前 Owner 持有的流水命令窗口
        ClusterCommandWindow window = commandPipeline.commandWindow();

        // 阶段一：批量排空与退休已就绪的队首命令，释放窗口容量
        if (!commandPipeline.hasCommittingHead() && window.size() != 0) {
            beginCommandCommit();
        }
        if (commandPipeline.hasCommittingHead()) {
            pollCommandCommitBatch(true, OwnerCommandPipelineState.COMPLETION_BATCH_SIZE);
        }
        if (commandPipeline.hasActiveControl()) {
            if (!pollControl()) return;
        }

        // 阶段二：紧凑批量填充 Ingress 命令，最大化流水线并发吞吐
        int admittedInputs = 0;
        while (admittedInputs < configuration.ownerInputBatchSize() && window.size() < window.capacity() && !commandPipeline.hasActiveControl()) {
            // 当前输入环借出的队首条目，取走或释放后不能继续访问
            var next = ingress.first();
            if (next == null) break;
            if (commandPipeline.isIngressBlocked(next.command())) break;
            commandPipeline.clearBlockedIngress();
            logContext.timestamp(next.timestamp());
            logContext.position(next.position());
            if (!next.ownerObserved()) {
                next.ownerObserved(true);
            }
            // 本条是否可以进入独立撮合窗口
            boolean eligible = state.prepareClusterPipelineScope(next.command(), window);
            // 当前 UUID 是否对应尚未完成的已登记撮合命令
            boolean retry = eligible && !state.pendingMatching().isEmpty()
                    && state.matchingSequence(next.command().header().commandIdMsb(), next.command().header().commandIdLsb()) != 0;
            // 当前命令必须等待的窗口前缀长度，零表示该栅栏已解除
            int prefix = !eligible || retry ? window.size() : window.conflictingPrefixSize();
            if (prefix != 0 && window.size() != 0) {
                if (!commandPipeline.hasCommittingHead()) {
                    beginCommandCommit();
                }
                if (pollCommandCommitBatch(true, OwnerCommandPipelineState.COMPLETION_BATCH_SIZE) > 0) {
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
                // 当前控制输入是否属于撮合命令，用来登记需要等待的撮合序号
                boolean matching = TradingCoreRuntime.isMatchingCommand(next.command().header().messageType());
                commandPipeline.rememberMatchingResponseSequence(matching ? state.matchingSequence(
                        next.command().header().commandIdMsb(), next.command().header().commandIdLsb()) : 0L);
                pollControl();
                break;
            }
            if (window.size() == 0) state.assertClusterCallbackComplete();
            // 接收当前命令及其输入租约的新窗口槽
            var entry = window.add(next.sessionId(), next.command(), next.timestamp(), next.position());
            // 移除输入条目前先保存的请求指纹，供幂等检查使用
            var fingerprint = next.fingerprint();
            ingress.remove(); // 命令及其解码租约已交给窗口，原输入条目可以释放。
            // 本次运行时执行或查询得到的结果，可能尚待提交
            CoreResponse result = state.applyDecodedCommand(entry.request, entry.timestamp, entry.position,
                    window.decoded(entry.request), true, fingerprint);
            // 本命令需要等待的撮合 Core 序号；无需撮合时为零
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

    /**
     * 仅在实时边界启用时，按本命令原日志位置和业务时间开启变更捕获。
     * @param position 本命令原复制日志位置
     * @param timestamp 本命令原业务时间，不用系统时钟替代
     */
    private void beginCapture(long position, long timestamp) {
        if (realtimeBoundary.enabled()) {
            realtimeBoundary.beginCapture(state, position, timestamp);
        }
    }

    /**
     * 把窗口当前提交头登记为提交阶段，并恢复该命令的实时捕获元数据及等待截止时间；此时不表示已经提交成功。
     */
    private void beginCommandCommit() {
        commandPipeline.beginHeadCommit();
        // 当前正在提交的窗口队首，名称不表示窗口尾部
        var last = commandPipeline.committingHead();
        beginCapture(last.position, last.timestamp);
        commandPipeline.startProgressDeadline();
    }

    /**
     * 按预算逐个检查窗口提交头；可提前派发财务后缀，但终态仍按队首顺序交出。等 Matcher、账户及账本条件满足后提交实时变化、交响应并释放窗口阶段。
     * @param advanceMatching 是否显式推进撮合与财务进度；批次首轮仍会进行必要探测
     * @param maxBatch 本轮最多退休的窗口命令数
     */
    private int pollCommandCommitBatch(boolean advanceMatching, int maxBatch) {
        // 本轮真正交出终态并退休的窗口条数
        int committedCount = 0;
        // 当前 Owner 持有的流水命令窗口
        ClusterCommandWindow window = commandPipeline.commandWindow();
        while (committedCount < maxBatch) {
            if (!commandPipeline.hasCommittingHead()) {
                if (window.size() == 0) break;
                beginCommandCommit();
            }
            // 当前正在提交的窗口队首，名称不表示窗口尾部
            var last = commandPipeline.committingHead();
            // 允许提前派发财务的最远撮合序号，不放宽终态提交顺序
            long dispatchThrough = Math.max(last.sequence, window.lastMatchingSequence());
            // 本次队首检查是否还需要刷新撮合和财务进度
            boolean adv = advanceMatching || committedCount == 0;
            if (!adv && last.sequence != 0) {
                adv = !state.commits.matchingCommitReady(state.pendingMatching().get(last.sequence));
            }
            if (last.sequence != 0) {
                // 已满足提交条件的撮合终态，空表示本轮继续等待
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

    /**
     * 推进唯一控制命令：等待直接控制续步、衍生撮合和盘口查询；条件齐备后提交实时帧及响应，释放账户访问权、输入和解码租约。暂未完成时保留原阶段。
     */
    private boolean pollControl() {
        // 当前唯一控制输入及其租约
        OwnerIngressRing.Entry activeControl = commandPipeline.activeControl();
        // 控制输入保存的原请求
        var request = activeControl.command();
        if (state.directCommand.active()) {
            commandPipeline.controlResponse(state.directCommand.poll());
            if (commandPipeline.controlResponse() == null) { checkProgressDeadline(); return false; }
        }
        state.commits.commitReadyMatching(OwnerCommandPipelineState.COMPLETION_BATCH_SIZE,
                activeControl.timestamp(), activeControl.position(), false, matchingCommitHandler);
        if (state.pendingMatching().firstSequence() != 0) { checkProgressDeadline(); return false; }
        if (commandPipeline.hasMatchingResponseSequence()) {
            if (commandPipeline.matchingResponse() == null) {
                throw new IllegalStateException("missing terminal callback result");
            }
            commandPipeline.controlResponse(commandPipeline.matchingResponse());
        }
        if (state.bookQueries.hasPendingQuery(request.header().commandId())) {
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

    /**
     * 每次检查线程中断，每 1024 次检查一次命令截止时间；首轮也检查时钟。超时作为推进故障抛出，并附带当前等待诊断。
     */
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

    /**
     * 拼出当前窗口、控制命令、Matcher、账户与完成队列的诊断信息，供卡住或超时定位；它是运行观测，不是可恢复的业务快照。
     */
    String stalledCommandDiagnostics() {
        // 当前 Owner 持有的流水命令窗口
        var window = commandPipeline.commandWindow();
        // 当前窗口提交头，空表示窗口没有在途命令
        var head = window == null || window.size() == 0 ? null : window.get(0);
        // 当前待撮合队列的队首，用于故障诊断
        var pending = state.pendingMatching().head();
        // 当前输入环队首的诊断引用
        var ingressHead = ingress.first();
        // 当前控制输入的诊断引用
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

    /**
     * 完成当前边界之前的业务输入、控制命令和流水窗口；遇到输入环的动作边界即停止，不越过该边界处理后面的命令。
     * @param deadline 单调纳秒时钟上的等待截止时间
     */
    private void finishSnapshotPrefix(long deadline) {
        while (hasCommandIngress() || commandPipeline.hasActiveControl()
                || commandPipeline.commandWindow().size() != 0) {
            progressCommands();
            idleCommand(deadline);
        }
    }

    /**
     * 返回输入环和流水窗口中的待处理条目数。动作边界也在输入环内，因此它是积压指标，不是精确的未回包业务数。
     */
    public int pendingCommandCount() {
        return ingress.size() + commandPipeline.commandWindow().size();
    }

    /**
     * 判断输入环是否有未处理的业务命令。
     */
    private boolean hasCommandIngress() {
        return ingress.first() != null;
    }

    /**
     * 返回流水窗口当前条数。
     */
    int commandWindowSize() {
        return commandPipeline.commandWindow().size();
    }

    /**
     * 返回命令流水线记录的窗口积压高水位，用于观测容量压力。
     */
    public int commandWindowHighWaterMark() { return commandPipeline.commandWindowHighWaterMark(); }

    /**
     * 借出命令流水线的窗口引用，供既有编排和测试查看；不复制也不转移所有权。
     */
    ClusterCommandWindow commandWindow() { return commandPipeline.commandWindow(); }

    /**
     * 借出实时边界的有界快照请求队列，由 Owner 在既有边界内消费。
     */
    public ManyToOneConcurrentArrayQueue<RealtimeFrame> snapshotRequests() {
        return realtimeBoundary.requests();
    }

    /**
     * 等待推进时先检查中断和显式截止时间，再调用忙等自旋；失败附带诊断，不把技术超时伪装成业务拒绝。
     * @param deadline 单调纳秒时钟上的等待截止时间
     */
    private void idleCommand(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // 这是运行时故障，不是复制的业务拒绝，也不能转换成延迟完成。
            throw new IllegalStateException("cluster log callback completion interrupted or timed out: "
                    + stalledCommandDiagnostics());
        }
        Thread.onSpinWait();
    }

    /**
     * 按默认快照等待期限捕获快照，并把分块结果合成一个字节数组。
     * @param snapshotId 本次快照身份，交给既有快照生命周期
     */
    public byte[] captureSnapshot(long snapshotId) {
        return captureSnapshot(snapshotId, snapshotDeadline());
    }

    /**
     * 按指定纳秒截止时间捕获分块快照，再合成完整字节数组。
     * @param snapshotId 本次快照身份，交给既有快照生命周期
     * @param deadlineNanos 单调纳秒时钟上的显式快照截止时间
     */
    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return captureSnapshotSections(snapshotId, deadlineNanos).toByteArray();
    }

    /**
     * 按默认快照等待期限返回分块结果，避免必须先合成整个快照。
     * @param snapshotId 本次快照身份，交给既有快照生命周期
     */
    public SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(long snapshotId) {
        return captureSnapshotSections(snapshotId, snapshotDeadline());
    }

    /**
     * 将快照秒数配置换算成当前单调时钟上的截止时间；加法溢出直接报错，本方法不额外校验配置正负范围。
     */
    private long snapshotDeadline() {
        return Math.addExact(System.nanoTime(),
                java.util.concurrent.TimeUnit.SECONDS.toNanos(configuration.snapshotTimeoutSeconds()));
    }

    /**
     * 快照主流程：保护重入、完成前缀、确认回调干净并启动快照；持续收集分块，等待或失败由快照生命周期判断，最后解除推进标记。
     * @param snapshotId 本次快照身份，交给既有快照生命周期
     * @param deadlineNanos 单调纳秒时钟上的显式快照截止时间
     */
    private SectionedCoreSnapshotCodec.SectionedSnapshot captureSnapshotSections(
            long snapshotId, long deadlineNanos) {
        finishSnapshotPrefix(deadlineNanos);
        state.assertClusterCallbackComplete();
        state.snapshots.beginSnapshot(snapshotId, deadlineNanos);
        while (true) {
            // 已准备好的分块快照，空表示需要继续收集
            SectionedCoreSnapshotCodec.SectionedSnapshot snapshot = state.snapshots.pollSnapshotSections(
                    logContext == null ? 0 : logContext.timestamp(),
                    logContext == null ? 0 : logContext.position(),
                    System.nanoTime());
            if (snapshot != null) return snapshot;
            Thread.onSpinWait();
        }
    }

    /**
     * 更新实时边界的角色并记录日志；外层负责更新 Owner 日志上下文和先前命令的完成边界。
     * @param newRole 复制日志边界确定的新 Cluster 角色
     */
    public void roleChange(Cluster.Role newRole) {
        realtimeBoundary.roleChange(newRole);
        log.info("Aeron core role-change productLine={} role={}", productLine, newRole);
    }

    /**
     * 用复制日志的业务时间和本轮单调时钟推进实时读取；不生成新的交易裁决。
     * @param nowNs 用于超时和调度观测的当前单调纳秒时间
     */
    private int progressRealtimeReads(long nowNs) {
        return realtimeBoundary.poll(state, logContext.timestamp(), nowNs);
    }

    /**
     * 记录摘要并关闭实时边界，然后关闭交易运行时；运行时关闭完成或抛错后清命令容器和输入租约。仍被工作线程借用的解码输入必须等运行时关闭后再释放。
     */
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

    /**
     * 只探测现有 Matcher 通知、撮合完成和 Lane 回执；运行时尚未建立时返回假，不在门禁里强制检查尚未激活的资源。
     */
    boolean ownerCompletionAvailable() {
        return state != null && (state.runtimeState.hasMatchingNotifications()
                || state.matcherPipeline().hasMatchingCompletions()
                || state.hasCompletedLaneCommit());
    }

    /**
     * 单轮推进入口：比较命令进度和派发修订，空闲时兼顾实时读取；返回是否有进展及实时工作量，不能当作本轮完成命令数。故障统一传给 Agent，最终保留队首解码缓存。
     */
    public int pollCommands() {
        // 本轮推进前的命令生命周期进度号
        long before = commandPipeline.commandProgress();
        // 本轮推进前的撮合派发修订号
        long matchingBefore = state.pendingMatching().dispatchRevision();
        try {
            progressCommands();
            // 本轮空闲阶段处理的实时读取数量
            int realtimeWork = pendingCommandCount() == 0 && realtimeBoundary.enabled()
                    ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != commandPipeline.commandProgress()
                    || matchingBefore != state.pendingMatching().dispatchRevision() ? 1 : 0)
                    + realtimeWork;
        } catch (/* 已是 Agent 终止异常的故障，保留原类型继续抛出 */ org.agrona.concurrent.AgentTerminationException fatal) {
            throw fatal;
        } catch (/* 当前读取或推进失败的异常，附带上下文后继续抛出 */ RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            retainPendingPreparation();
        }
    }

    /**
     * 使用默认快照期限完成当前动作边界之前的命令，供角色切换、快照及退出边界复用。
     */
    public void finishCommands() { finishSnapshotPrefix(snapshotDeadline()); }

    /**
     * 取得已启动运行时；尚未启动则明确报错。引用仍归 Owner，不提供跨线程账户读取权限。
     */
    public TradingCoreRuntime state() {
        if (state == null) throw new IllegalStateException("clustered service is not started");
        return state;
    }

    /**
     * 把已有实时 outbox 及可空变更捕获器交给实时边界，不另建交易状态。
     * @param outbox 接入的实时输出队列，沿用既有所有权
     * @param capture 可空实时变更捕获器
     */
    public void attachRealtime(RealtimeOutbox outbox,
                        RealtimeStateCapture capture) {
        realtimeBoundary.attach(outbox, capture, state);
    }

    /**
     * 运行时仍在时归还响应缓冲租约；已关闭或尚未启动时不再访问运行时池。
     * @param response 要交出或归还的终态响应及其缓冲租约
     */
    public void releaseResponse(CoreResponse response) {
        if (state != null) state.releaseResponse(response);
    }

    /**
     * 从完整快照字节恢复指定产品线的候选运行时，再通过统一替换入口接入。
     * @param snapshot 完整快照的编码字节
     */
    void restoreSnapshot(byte[] snapshot) {
        replaceState(SectionedCoreSnapshotCodec.decodePassive(snapshot, productLine, configuration, startupTopology));
    }

    /**
     * 要求恢复缓冲非空，解码候选运行时后交给统一替换入口。
     * @param recovery 已收齐片段的恢复缓冲
     */
    public void restoreSnapshot(SectionedCoreSnapshotCodec.RecoveryBuffer recovery) {
        if (recovery == null) throw new IllegalArgumentException("snapshot recovery is null");
        replaceState(recovery.decodePassive(productLine, configuration, startupTopology));
    }

    /**
     * 先关闭旧运行时，再接入恢复结果并重新绑定实时状态；旧运行时关闭失败时不会先把新对象设为当前状态。
     * @param restored 已解码的候选运行时
     */
    private void replaceState(TradingCoreRuntime restored) {
        state.close();
        state = restored;
        realtimeBoundary.rebindState(state);
    }

    /**
     * 用运行时当前已提交 Core 序号，把原请求和终态交给统一响应出口。
     * @param sessionId 响应目标会话 ID；无会话命令使用 NO_SESSION_ID
     * @param request 产生该响应的原请求或请求头
     * @param response 要交出或归还的终态响应及其缓冲租约
     */
    private void offerResponse(long sessionId, CoreMessage request, CoreResponse response) {
        publishResponse(responseSink, sessionId, request, response, state.committedCoreSequence());
    }

    /**
     * 从请求借出对应类型的响应头并交给出口；异步出口要保留头部和响应租约直到编码完成，本方法不做网络发送。
     * @param sink 要接收头部和响应租约的出口
     * @param sessionId 响应目标会话 ID；无会话命令使用 NO_SESSION_ID
     * @param request 产生该响应的原请求或请求头
     * @param response 要交出或归还的终态响应及其缓冲租约
     * @param committedSequence 响应对应的已提交 Core 序号
     */
    public static void publishResponse(ResponseSink sink, long sessionId, CoreMessage request,
                                CoreResponse response, long committedSequence) {
        // 按响应消息类型从原请求借出的响应头，异步编码前不能归还
        CoreMessageHeader header = request.responseHeader(responseType(request.header()));
        sink.offer(sessionId, header, response, committedSequence);
    }

    /**
     * 把查询请求映射到对应查询结果类型，其余查询使用状态哈希结果类型，普通命令使用 COMMAND_RESULT。
     * @param requestHeader 需要决定响应类型的请求头
     */
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
