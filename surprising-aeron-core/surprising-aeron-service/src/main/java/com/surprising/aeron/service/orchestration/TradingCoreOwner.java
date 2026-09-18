package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import java.util.function.BooleanSupplier;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * 交易 Owner：按确定性边界推进交易，未完成命令与响应跨推进轮次保留。
 *
 * <p>该类不实现 {@link ClusteredService}。Aeron 回调、会话和网络出口由
 * {@link AeronTradingClusterService} 持有；Owner 只接收已复制的不可变命令、
 * 推进交易状态并产生终态交接。</p>
 */
public final class TradingCoreOwner {

    private static final int MATCHING_COMPLETION_BATCH_SIZE = 64;
    private static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;
    private final ClusterCommandWindow commandWindow = new ClusterCommandWindow();
    private int commandWindowHighWaterMark;
    private long drainedWindows, drainedCommands, dependencyFences, controlFences;
    private long retryFences;
    /** Owner 独占：只有准入、控制执行或提交推进时递增，等待中的命令不算工作。 */
    private long commandProgress;
    /** 已提交结果的异步出口，网络背压不占用交易回调的执行时间。 */
    private final DeferredSessionResponses pendingResponses = new DeferredSessionResponses();

    /** 不可变终态跨线程出口；为空时由当前线程直接编码并使用会话。 */
    private final ResponseSink responseSink;
    @FunctionalInterface
    interface ResponseSink {
        void offer(ClientSession session, CoreMessageHeader header, CoreResponse response, long committedSequence);
    }

    private final ProductLine productLine;
    private TradingCoreRuntime state;
    private com.surprising.aeron.client.RealtimeOutbox realtimeOutbox;
    private com.surprising.aeron.client.AeronRealtimeSender realtimeSender;
    private com.surprising.aeron.client.AeronRealtimeReceiver realtimeControl;
    private com.surprising.aeron.service.state.realtime.RealtimeStateCapture realtimeCapture;
    private final org.agrona.concurrent.ManyToOneConcurrentArrayQueue<com.surprising.aeron.protocol.RealtimeFrame>
            snapshotRequests = new org.agrona.concurrent.ManyToOneConcurrentArrayQueue<>(256);
    private boolean realtimeLeader;
    private long lastCommittedPosition;
    /** 仅 Owner 写入：上一轮已耗尽本地推进时的命令前缀、派发上限和进度。 */
    private long waitingPrefixSequence, waitingDispatchSequence, waitingMatchingProgress;
    /** 没有本地进展时只探测完成通知，仍持续接收入站命令，不使用定时器。 */
    private boolean waitingMatchingNotification;
    private long nextRealtimeSnapshotNs;
    // Aeron's cluster idle strategy can reenter doBackgroundWork on this same thread.
    private boolean processingLogCallback;

    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private byte[] responseScratch = new byte[4 * 1024];
    private final UnsafeBuffer responseBuffer = new UnsafeBuffer(responseScratch);
    // 跨回调保留控制命令的终态关联；普通订单使用 commandWindow。
    private long responseSequence;
    private CoreResponse matchingResponse;
    private final TradingCoreRuntime.MatchingCommitHandler matchingCommitHandler = this::completeMatching;
    private long snapshotFenceNotReadyCount;
    private long snapshotFenceTimeoutCount;

    public TradingCoreOwner(ProductLine productLine) { this(productLine, null); }

    TradingCoreOwner(ProductLine productLine, ResponseSink responseSink) {
        this.responseSink = responseSink;
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
    }

    void start(Cluster cluster) {
        this.cluster = cluster;
        if (state == null) state = new TradingCoreRuntime(productLine);
        responseSequence = 0;
        matchingResponse = null;
        processingLogCallback = false;
        commandWindow.clear();
        pendingIngress.clear();
        blockedIngress = blockedWindowHead = null;
        activeControl = null;
        controlResponse = null;
        drainingSize = 0;
        pendingResponses.clear();
        commandWindowHighWaterMark = 0;
        drainedWindows = drainedCommands = dependencyFences = controlFences = retryFences = 0;
        snapshotFenceNotReadyCount = 0;
        snapshotFenceTimeoutCount = 0;
        idleStrategy = cluster.idleStrategy();
        System.out.printf("Aeron core role productLine=%s role=%s%n", productLine, cluster.role());
        lastCommittedPosition = Math.max(0, cluster.logPosition());
        realtimeLeader = cluster.role() == Cluster.Role.LEADER;
        String channel = System.getProperty("surprising.realtime.channel", "");
        if (!channel.isBlank() && realtimeOutbox == null) {
            realtimeOutbox = new com.surprising.aeron.client.RealtimeOutbox(8192, 8 * 1024 * 1024);
            realtimeCapture = state.attachRealtime(realtimeOutbox);
            String directory = System.getProperty("surprising.realtime.directory", io.aeron.CommonContext.getAeronDirectoryName());
            realtimeSender = new com.surprising.aeron.client.AeronRealtimeSender(realtimeOutbox, directory,
                    channel, Integer.getInteger("surprising.realtime.stream", 2101));
            String control = System.getProperty("surprising.realtime.control-channel", "");
            if (!control.isBlank()) realtimeControl = new com.surprising.aeron.client.AeronRealtimeReceiver(
                    directory, control, Integer.getInteger("surprising.realtime.control-stream", 2102), frame -> {
                        if (frame.productLine() == productLine && frame.snapshotId() > 0 && frame.payloadLength() == 0
                                && frame.ordinal() == 0 && frame.sequence() == 0
                                && ((frame.userId() > 0 && frame.kind() == com.surprising.aeron.protocol.RealtimeFrame.Kind.SNAPSHOT_REQUEST)
                                || (frame.userId() == 0 && frame.kind() == com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK_REQUEST
                                    && frame.symbol().matches("[A-Z0-9][A-Z0-9_-]{1,63}"))))
                            snapshotRequests.offer(frame);
                    });
        }
    }

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

    private void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                        CommandFingerprint fingerprint, boolean advance) {
        try {
            processingLogCallback = true;
            pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            processIngress(session, request, timestamp, position, fingerprint, advance);
        } catch (org.agrona.concurrent.AgentTerminationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // AgentRunner otherwise logs ordinary exceptions and may consume the next message.
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally {
            retainPendingPreparation();
            processingLogCallback = false;
        }
    }

    /** 已复制入口及当前控制命令由服务线程持有，跨回调保留原始日志上下文。 */
    private final PendingClusterIngress pendingIngress = new PendingClusterIngress();
    /** 当前已执行一次、仍在等待子任务或查询结果的控制命令。 */
    private PendingClusterIngress.Entry activeControl;
    /** 控制命令最终响应的候选值；子任务终态收齐前不发送。 */
    private CoreResponse controlResponse;
    /** 正在提交的确定性窗口前缀；不因下一次轮询的线程速度改变边界。 */
    private int drainingSize;
    private long drainingSequence, progressDeadline;
    /** 已判定必须等待的入口及当时窗口队首；队首提交前不重复准备同一依赖范围。 */
    private CoreMessage blockedIngress, blockedWindowHead;

    private void retainPendingPreparation() {
        var next = pendingIngress.first();
        CoreMessage pending = next == null ? null : next.command;
        commandWindow.retainDecoded(pending);
        if (pending != blockedIngress || pending == null) {
            blockedIngress = blockedWindowHead = null;
        }
    }

    private void processIngress(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint, boolean advance) {
        awaitIngressCapacity(request);
        pendingIngress.add(session, request, timestamp, position, fingerprint);
        if (advance) progressCommands(false);
    }

    /** 队列满时暂停日志消费，在原日志上下文内推进已复制命令；不改变业务顺序或拒绝结果。 */
    private void awaitIngressCapacity(CoreMessage request) {
        if (pendingIngress.hasCapacity(request)) return;
        long deadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
        do {
            progressCommands(false);
            if (pendingIngress.hasCapacity(request)) return;
            idleCommand(0, deadline);
        } while (true);
    }

    private void progressCommands(boolean flushWindow) {
        state.bindOwner();
        state.runtimeState.enterAsynchronousCommandScope();
        try { progressCommandsInScope(flushWindow); }
        finally { state.runtimeState.exitAsynchronousCommandScope(); }
    }

    private void progressCommandsInScope(boolean flushWindow) {
        boolean awaitingCompletion = false;
        for (int work = 0; work < MATCHING_COMPLETION_BATCH_SIZE; work++) {
            // 已确定的提交前缀不变；未完成时仍可准入与在途命令无依赖的后续订单。
            if (drainingSize == 0 && commandWindow.size() != 0) beginCommandPrefix();
            // 本轮未收到完成时仍准入独立命令；不为每个新准入项重跑整套完成收集。
            if (drainingSize != 0 && !awaitingCompletion) {
                // Retire ready results before adding more work; the existing turn budget bounds this drain.
                if (pollCommandPrefix()) continue;
                awaitingCompletion = true;
            }
            if (commandWindow.size() == commandWindow.capacity()) return;
            if (activeControl != null) {
                if (!pollControl()) return;
                continue;
            }
            var next = pendingIngress.first();
            if (next == null) return;
            if (next.command == blockedIngress && commandWindow.size() != 0
                    && commandWindow.get(0).request == blockedWindowHead) return;
            blockedIngress = blockedWindowHead = null;
            boolean eligible = state.prepareClusterPipelineScope(next.command, commandWindow);
            boolean retry = eligible && state.matchingSequence(next.command.header().commandId()) != 0;
            int prefix = !eligible || retry ? commandWindow.size() : commandWindow.conflictingPrefixSize();
            if (prefix != 0) {
                blockedIngress = next.command;
                blockedWindowHead = commandWindow.get(0).request;
                // A prefix is normally already draining. Count the decision before returning,
                // otherwise the busiest dependency waits disappear from the diagnostics.
                if (!eligible) controlFences++;
                else {
                    dependencyFences++;
                    if (retry) retryFences++;
                }
                if (drainingSize != 0) return;
                beginCommandPrefix();
                continue;
            }
            if (!eligible) {
                if (state.requiresOwnerLaneAccessForPreparation(next.command)
                        && !state.runtimeState.tryAcquireOwnerLaneAccess()) return;
                CoreMatchingPhaseMetrics.recordBoundary("ingressToControl", next.command.header(), next.queuedNanos);
                activeControl = next;
                state.assertClusterCallbackComplete();
                beginCapture(next.position, next.timestamp);
                progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
                controlResponse = state.applyDecodedCommand(next.command, next.timestamp, next.position,
                        commandWindow.decodedIfPresent(next.command), false, next.fingerprint);
                commandProgress++;
                responseSequence = state.matchingSequence(next.command.header().commandId());
                matchingResponse = null;
                continue;
            }
            if (commandWindow.size() == 0) state.assertClusterCallbackComplete();
            CoreMatchingPhaseMetrics.recordBoundary("ingressToAdmission", next.command.header(), next.queuedNanos);
            long admissionStart = CoreMatchingPhaseMetrics.sampleStart(next.command.header());
            var entry = commandWindow.add(next.session, next.command, next.timestamp, next.position);
            CoreResponse result = state.applyDecodedCommand(next.command, next.timestamp, next.position,
                    commandWindow.decoded(next.command), true, next.fingerprint);
            commandWindow.bindSequence(entry, state.matchingSequence(next.command.header().commandId()));
            if (entry.sequence != 0) {
                var pending = state.pendingMatching(entry.sequence);
                pending.establishCommitFence(next.timestamp, next.position);
                state.pendingMatching.partitionDependenciesChanged();
            }
            CoreMatchingPhaseMetrics.recordBoundary("admissionExecution", next.command.header(), admissionStart);
            entry.response = entry.sequence == 0 ? result : null;
            pendingIngress.remove();
            commandProgress++;
            commandWindowHighWaterMark = Math.max(commandWindowHighWaterMark, commandWindow.size());
        }
    }

    private void beginCapture(long position, long timestamp) {
        if (realtimeCapture != null && realtimeLeader) {
            try { realtimeCapture.begin(position, timestamp, 0, state.realtimeExportSequence()); }
            catch (RuntimeException failure) { realtimeCapture.failed(); }
        }
    }

    /** 每条命令有固定提交边界，不能让各副本的线程完成速度改变推送分组。 */
    private void beginCommandPrefix() {
        if (commandWindow.size() == 0 || drainingSize != 0)
            throw new IllegalStateException("invalid command drain boundary");
        drainingSize = 1;
        var last = commandWindow.get(0);
        drainingSequence = last.sequence;
        drainedWindows++;
        drainedCommands++;
        beginCapture(last.position, last.timestamp);
        progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
    }

    private boolean pollCommandPrefix() {
        var last = commandWindow.get(drainingSize - 1);
        long dispatchThrough = Math.max(drainingSequence, commandWindow.lastMatchingSequence());
        long progressBefore = state.matchingProgressSequence();
        if (waitingMatchingNotification && waitingPrefixSequence == drainingSequence
                && waitingDispatchSequence == dispatchThrough && waitingMatchingProgress == progressBefore
                && !state.hasMatchingNotifications()) {
            checkProgressDeadline();
            return false;
        }
        waitingMatchingNotification = false;
        state.commits.commitReadyMatching(MATCHING_COMPLETION_BATCH_SIZE,
                last.timestamp, last.position, false, drainingSequence,
                dispatchThrough, matchingCommitHandler);
        if (state.firstPendingMatchingSequence() != 0 && state.firstPendingMatchingSequence() <= drainingSequence) {
            waitingMatchingProgress = state.matchingProgressSequence();
            waitingPrefixSequence = drainingSequence;
            waitingDispatchSequence = dispatchThrough;
            waitingMatchingNotification = waitingMatchingProgress == progressBefore && !state.hasLocalMatchingWork();
            checkProgressDeadline();
            return false;
        }
        if (drainingSize == commandWindow.size()) state.assertClusterCallbackComplete();
        lastCommittedPosition = last.position;
        if (realtimeCapture != null) realtimeCapture.commit(state.realtimeExportSequence());
        for (int i = 0; i < drainingSize; i++) {
            var entry = commandWindow.get(i);
            if (entry.response == null) throw new IllegalStateException("missing pipeline terminal response");
            if (entry.session != null) offerResponse(entry.session,
                    entry.request.header().response(responseType(entry.request.header())), entry.response);
        }
        commandWindow.removePrefix(drainingSize);
        commandProgress++;
        drainingSize = 0;
        drainingSequence = 0;
        state.runtimeState.releaseCompletedSequentialLaneStage();
        if (commandWindow.size() == 0) progressRealtimeReads(System.nanoTime());
        return true;
    }

    private boolean pollControl() {
        var request = activeControl.command;
        if (state.hasPendingDirectCommand()) {
            controlResponse = state.pollDirectCommand();
            if (controlResponse == null) { checkProgressDeadline(); return false; }
        }
        state.commits.commitReadyMatching(MATCHING_COMPLETION_BATCH_SIZE,
                activeControl.timestamp, activeControl.position, false, matchingCommitHandler);
        if (state.firstPendingMatchingSequence() != 0) { checkProgressDeadline(); return false; }
        if (responseSequence != 0) {
            if (matchingResponse == null) throw new IllegalStateException("missing terminal callback result");
            controlResponse = matchingResponse;
        }
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            CoreResponse result = state.takeQueryResult(querySequence);
            if (result == null) { checkProgressDeadline(); return false; }
            controlResponse = result;
        }
        state.assertClusterCallbackComplete();
        lastCommittedPosition = activeControl.position;
        if (realtimeCapture != null) realtimeCapture.commit(state.realtimeExportSequence());
        if (activeControl.session != null) offerResponse(activeControl.session,
                request.header().response(responseType(request.header())), controlResponse);
        state.runtimeState.releaseOwnerLaneAccess();
        activeControl = null;
        controlResponse = matchingResponse = null;
        responseSequence = 0;
        pendingIngress.remove();
        commandProgress++;
        return true;
    }

    private void checkProgressDeadline() {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - progressDeadline >= 0)
            throw new IllegalStateException("asynchronous command progress interrupted or timed out");
    }

    /** 仅框架快照边界需要等待：快照不能遗漏已经复制但尚未完成的业务。 */
    private void finishSnapshotPrefix(long deadline) {
        while (pendingIngress.size() != 0 || commandWindow.size() != 0 || activeControl != null) {
            progressCommands(true);
            idleCommand(0, deadline);
        }
    }

    /** 已复制但尚未返回终态的命令及日志推进边界数量，用于运行积压观测。 */
    public int pendingCommandCount() { return pendingIngress.size() + commandWindow.size(); }

    /**
     * Owner 在调用完整推进器前的廉价门禁。
     *
     * <p>在途窗口本身不是工作：队首等待 Matcher/Lane 时再次调用
     * {@link #pollCommands()} 只会重复探测同一个未就绪前缀。新的、尚未判定为该前缀
     * 阻塞的入站命令仍需要一次推进；完成通知或 Owner 本地可推进状态则由 Runtime
     * 提供其余唤醒来源。</p>
     */
    boolean ownerWorkAvailable() {
        if (pendingIngressReady()) return true;
        // pollCommandPrefix already observed an unchanged completion cursor.  Until a producer
        // publishes a new notification, entering the full command scope cannot make progress.
        if (waitingMatchingNotification) return ownerCompletionAvailable();
        return state != null && state.hasMatchingDrainWork();
    }

    /**
     * 已被当前队首依赖挡住的入站命令不能把 Owner 重新拉入完整推进循环；队首完成后
     * {@link #ownerCompletionAvailable()} 或 Runtime 的本地进展门禁会再次放行。
     */
    private boolean pendingIngressReady() {
        var next = pendingIngress.first();
        if (next == null) return false;
        if (commandWindow.size() == commandWindow.capacity()) return false;
        return next.command != blockedIngress
                || commandWindow.size() == 0
                || commandWindow.get(0).request != blockedWindowHead;
    }

    int commandWindowSize() { return commandWindow.size(); }
    int commandWindowHighWaterMark() { return commandWindowHighWaterMark; }
    ClusterCommandWindow commandWindow() { return commandWindow; }
    org.agrona.concurrent.ManyToOneConcurrentArrayQueue<com.surprising.aeron.protocol.RealtimeFrame>
    snapshotRequests() { return snapshotRequests; }

    private void completeMatching(long sequence, CoreResponse response) {
        if (commandWindow.size() != 0) {
            commandWindow.complete(sequence, response);
            return;
        }
        if (sequence == responseSequence) matchingResponse = response;
    }

    private void idleCommand(int work, long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            // Operational failure, never a replicated business rejection or a deferred completion.
            throw new IllegalStateException("cluster log callback completion interrupted or timed out");
        }
        idleStrategy.idle(work);
    }

    byte[] captureSnapshot(long snapshotId) {
        return captureSnapshot(snapshotId, snapshotDeadline());
    }

    byte[] captureSnapshot(long snapshotId, long deadlineNanos) {
        return captureSnapshotSections(snapshotId, deadlineNanos).toByteArray();
    }

    private long snapshotDeadline() {
        return Math.addExact(System.nanoTime(),
                java.util.concurrent.TimeUnit.SECONDS.toNanos(SNAPSHOT_TIMEOUT_SECONDS));
    }

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

    public long snapshotFenceNotReadyCount() {
        return snapshotFenceNotReadyCount;
    }

    public long snapshotFenceTimeoutCount() {
        return snapshotFenceTimeoutCount;
    }

    void roleChange(Cluster.Role newRole) {
        realtimeLeader = newRole == Cluster.Role.LEADER;
        pendingResponses.clear();
        if (realtimeCapture != null) realtimeCapture.abort();
        System.out.printf("Aeron core role-change productLine=%s role=%s%n", productLine, newRole);
    }

    int pollBackgroundWork(long nowNs) {
        if (processingLogCallback || pendingIngress.size() != 0 || commandWindow.size() != 0 || realtimeCapture == null || !realtimeLeader) return 0;
        return progressRealtimeReads(nowNs);
    }

    private int progressRealtimeReads(long nowNs) {
        if (realtimeCapture == null || !realtimeLeader) return 0;
        int work=state.pollRealtimeSnapshot()+state.pollRealtimeBook();
        if (state.realtimeSnapshotPending() || state.realtimeBookPending() || nowNs < nextRealtimeSnapshotNs) return work;
        var request = snapshotRequests.poll();
        if (request == null) return 0;
        nextRealtimeSnapshotNs = nowNs + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10);
        if(request.kind()==com.surprising.aeron.protocol.RealtimeFrame.Kind.BOOK_REQUEST)
            state.captureRealtimeBook(request.symbol(),lastCommittedPosition,cluster.time());
        else state.captureRealtimeSnapshot(request.userId(), request.snapshotId(), lastCommittedPosition, cluster.time());
        return 1;
    }

    void terminate() {
        System.out.printf("Aeron core command-window productLine=%s highWaterMark=%d pending=%d windows=%d commands=%d dependencyFences=%d controlFences=%d retryFences=%d%n",
                productLine, commandWindowHighWaterMark, commandWindow.size(), drainedWindows,
                drainedCommands, dependencyFences, controlFences, retryFences);
        commandWindow.clear();
        pendingIngress.clear();
        blockedIngress = blockedWindowHead = null;
        activeControl = null;
        controlResponse = null;
        drainingSize = 0;
        pendingResponses.clear();
        responseSequence = 0;
        matchingResponse = null;
        this.cluster = null;
        if (realtimeControl != null) { realtimeControl.close(); realtimeControl = null; }
        if (realtimeSender != null) { realtimeSender.close(); realtimeSender = null; }
        realtimeCapture = null;
        realtimeOutbox = null;
        if (state != null) {
            state.close();
            state = null;
        }
    }

    void sessionOpened() {
        processingLogCallback = true;
        try {
            pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            progressCommands(false);
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally { processingLogCallback = false; }
    }

    void sessionClosed(long sessionId) {
        if (responseSink == null) pendingResponses.remove(sessionId);
        drainFromLogEvent();
    }

    private void drainFromLogEvent() { pollCommands(); }

    /** 只能由持有交易状态的Owner线程调用；不属于Aeron的背景回调。 */
    void ownerCompletionSignal(Runnable signal) {
        state.runtimeState.ownerCompletionSignal(signal);
        state.matcherPipeline.completionSignal(signal);
    }

    /** 启动尚未收到首条日志时journal未激活；休眠探测只能读完成队列，不能执行运行期健康门禁。 */
    boolean ownerCompletionAvailable() {
        return state != null && (state.runtimeState.hasMatchingNotifications()
                || state.matcherPipeline.hasMatchingCompletions());
    }

    int pollCommands() {
        long before = commandProgress;
        long matchingBefore = state.matchingProgressSequence();
        processingLogCallback = true;
        try {
            int responseWork = pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            progressCommands(false);
            int realtimeWork = pendingCommandCount() == 0 ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != commandProgress || matchingBefore != state.matchingProgressSequence() ? 1 : 0)
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

    TradingCoreRuntime state() {
        if (state == null) throw new IllegalStateException("clustered service is not started");
        return state;
    }

    void attachRealtime(com.surprising.aeron.client.RealtimeOutbox outbox,
                        com.surprising.aeron.service.state.realtime.RealtimeStateCapture capture) {
        realtimeOutbox = outbox;
        realtimeCapture = capture == null ? state.attachRealtime(outbox) : capture;
    }

    void releaseResponse(CoreResponse response) {
        if (state != null) state.releaseResponse(response);
    }

    void loadSnapshot(SnapshotFragmentSource snapshotSource, BooleanSupplier endOfStream) {
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        while (!endOfStream.getAsBoolean()) {
            int fragments = snapshotSource.poll(
                    (buffer, offset, length, header) -> recovery.accept(buffer, offset, length), 10);
            idleStrategy.idle(fragments);
        }
        replaceState(recovery.decode(productLine));
    }

    static void ensureSnapshotCapacity(int currentLength, int fragmentLength) {
        if (currentLength < 0 || fragmentLength < 0
                || currentLength > CoreStateSnapshotCodec.MAX_SNAPSHOT_BYTES - fragmentLength) {
            throw new IllegalStateException("Aeron core snapshot exceeds maximum size");
        }
    }

    void restoreSnapshot(byte[] snapshot) {
        replaceState(TradingCoreRuntime.fromSnapshot(productLine, snapshot));
    }

    private void replaceState(TradingCoreRuntime restored) {
        state.close();
        state = restored;
        if (realtimeOutbox != null) realtimeCapture = state.attachRealtime(realtimeOutbox);
    }

    @FunctionalInterface
    interface SnapshotFragmentSource {
        int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    private void offerResponse(ClientSession session, CoreMessageHeader header, CoreResponse response) {
        if (responseSink != null) {
            responseSink.offer(session, header, response, state.committedCoreSequence());
            return;
        }
        if (session.isClosing()) {
            state.releaseResponse(response);
            return;
        }
        int length = CoreMessageCodec.encodedResponseLength(response);
        if (responseScratch.length < length) {
            responseScratch = new byte[length];
            responseBuffer.wrap(responseScratch);
        }
        try {
            CoreMessageCodec.encodeResponse(header, response, state.committedCoreSequence(), responseScratch);
            pendingResponses.offer(session, responseBuffer, length, System.nanoTime());
        } finally {
            state.releaseResponse(response);
        }
    }

    private static CoreMessageType responseType(com.surprising.aeron.protocol.CoreMessageHeader requestHeader) {
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
