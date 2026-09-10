package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.function.BooleanSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/** 集群日志入口：按确定性边界推进交易，未完成命令与响应跨回调保留。 */
public final class SurprisingClusteredService implements ClusteredService {

    private static final int MATCHING_COMPLETION_BATCH_SIZE = 64;
    private static final long COMMAND_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;
    private final ClusterCommandWindow commandWindow = new ClusterCommandWindow();
    private int commandWindowHighWaterMark;
    private long drainedWindows, drainedCommands, dependencyFences, controlFences;
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

    public SurprisingClusteredService(ProductLine productLine) { this(productLine, null); }

    SurprisingClusteredService(ProductLine productLine, ResponseSink responseSink) {
        this.responseSink = responseSink;
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
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
        drainedWindows = drainedCommands = dependencyFences = controlFences = 0;
        snapshotFenceNotReadyCount = 0;
        snapshotFenceTimeoutCount = 0;
        idleStrategy = cluster.idleStrategy();
        System.out.printf("Aeron core role productLine=%s role=%s%n", productLine, cluster.role());
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
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

    @Override
    public void onSessionMessage(
            ClientSession session,
            long timestamp,
            DirectBuffer buffer,
            int offset,
            int length,
            Header header) {
        CoreMessage request;
        try {
            request = CoreMessageFlyweightDecoder.decode(buffer, offset, length);
        } catch (IllegalArgumentException exception) {
            return;
        }
        acceptCommittedCommand(session, request, timestamp, header.position());
    }

    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position) {
        acceptCommittedCommand(session, request, timestamp, position, null);
    }

    /** 指纹来自服务内部解码后的日志记录，不能接受客户端提供的摘要。 */
    void acceptCommittedCommand(ClientSession session, CoreMessage request, long timestamp, long position,
                                CommandFingerprint fingerprint) {
        try {
            processingLogCallback = true;
            pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            processIngress(session, request, timestamp, position, fingerprint);
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
                                CommandFingerprint fingerprint) {
        awaitIngressCapacity(request);
        pendingIngress.add(session, request, timestamp, position, fingerprint);
        progressCommands(false);
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
        for (int work = 0; work < MATCHING_COMPLETION_BATCH_SIZE; work++) {
            // 已确定的提交前缀不变；未完成时仍可准入与在途命令无依赖的后续订单。
            if (drainingSize == 0 && commandWindow.size() != 0) beginCommandPrefix();
            if (drainingSize != 0) pollCommandPrefix();
            if (commandWindow.size() == ClusterCommandWindow.CAPACITY) return;
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
            int prefix = !eligible || state.matchingSequence(next.command.header().commandId()) != 0
                    ? commandWindow.size() : commandWindow.conflictingPrefixSize();
            if (prefix != 0) {
                blockedIngress = next.command;
                blockedWindowHead = commandWindow.get(0).request;
                if (drainingSize != 0) return;
                if (eligible) dependencyFences++; else controlFences++;
                beginCommandPrefix();
                continue;
            }
            if (!eligible) {
                if (state.requiresOwnerLaneAccessForPreparation(next.command)
                        && !state.runtimeState.tryAcquireOwnerLaneAccess()) return;
                activeControl = next;
                state.assertClusterCallbackComplete();
                beginCapture(next.position, next.timestamp);
                progressDeadline = System.nanoTime() + COMMAND_TIMEOUT_NANOS;
                controlResponse = state.applyDecodedCommand(next.command, next.timestamp, next.position,
                        commandWindow.decodedIfPresent(next.command), false, next.fingerprint);
                responseSequence = state.matchingSequence(next.command.header().commandId());
                matchingResponse = null;
                continue;
            }
            if (commandWindow.size() == 0) state.assertClusterCallbackComplete();
            var entry = commandWindow.add(next.session, next.command, next.timestamp, next.position);
            CoreResponse result = state.applyDecodedCommand(next.command, next.timestamp, next.position,
                    commandWindow.decoded(next.command), true, next.fingerprint);
            entry.sequence = state.matchingSequence(next.command.header().commandId());
            if (entry.sequence != 0) state.pendingMatching(entry.sequence).establishCommitFence(next.timestamp, next.position);
            entry.response = entry.sequence == 0 ? result : null;
            pendingIngress.remove();
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
        state.commits.commitReadyMatching(MATCHING_COMPLETION_BATCH_SIZE,
                last.timestamp, last.position, false, drainingSequence,
                Math.max(drainingSequence, commandWindow.lastMatchingSequence()), matchingCommitHandler);
        if (state.firstPendingMatchingSequence() != 0 && state.firstPendingMatchingSequence() <= drainingSequence) {
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

    int commandWindowSize() { return commandWindow.size(); }
    int commandWindowHighWaterMark() { return commandWindowHighWaterMark; }

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

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        SectionedCoreSnapshotCodec.SectionedSnapshot snapshot =
                captureSnapshotSections(Math.max(1, cluster.logPosition()), snapshotDeadline());
        idleStrategy.reset();
        for (byte[] sectionChunk : snapshot.chunks()) {
            UnsafeBuffer buffer = new UnsafeBuffer(sectionChunk);
            int offset = 0;
            while (offset < sectionChunk.length) {
                int chunkLength = Math.min(snapshotPublication.maxPayloadLength(), sectionChunk.length - offset);
                long result;
                while ((result = snapshotPublication.offer(buffer, offset, chunkLength)) < 0) {
                    if (!retryableOffer(result)) {
                        throw new IllegalStateException("snapshot publication failed: " + result);
                    }
                    idleStrategy.idle();
                }
                offset += chunkLength;
            }
        }
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

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        realtimeLeader = newRole == Cluster.Role.LEADER;
        pendingResponses.clear();
        if (realtimeCapture != null) realtimeCapture.abort();
        System.out.printf("Aeron core role-change productLine=%s role=%s%n", productLine, newRole);
    }

    @Override
    public int doBackgroundWork(long nowNs) {
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

    @Override
    public void onTerminate(Cluster cluster) {
        System.out.printf("Aeron core command-window productLine=%s highWaterMark=%d pending=%d windows=%d commands=%d dependencyFences=%d controlFences=%d%n",
                productLine, commandWindowHighWaterMark, commandWindow.size(), drainedWindows,
                drainedCommands, dependencyFences, controlFences);
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

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        processingLogCallback = true;
        try {
            pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            progressCommands(false);
        } catch (RuntimeException failure) {
            throw new org.agrona.concurrent.AgentTerminationException(failure);
        } finally { processingLogCallback = false; }
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        if (responseSink == null) pendingResponses.remove(session.id());
        drainFromLogEvent();
    }

    /** 历史日志中的定时器不产生交易操作；交易由独立Owner持续推进。 */
    @Override
    public void onTimerEvent(long correlationId, long timestamp) {}

    private void drainFromLogEvent() { pollCommands(); }

    /** 只能由持有交易状态的Owner线程调用；不属于Aeron的背景回调。 */
    int pollCommands() {
        int before = pendingCommandCount();
        processingLogCallback = true;
        try {
            pendingResponses.poll(System.nanoTime(), MATCHING_COMPLETION_BATCH_SIZE);
            progressCommands(false);
            int realtimeWork = pendingCommandCount() == 0 ? progressRealtimeReads(System.nanoTime()) : 0;
            return (before != 0 || pendingCommandCount() != 0 ? 1 : 0) + realtimeWork;
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

    private void loadSnapshot(Image snapshotImage) {
        loadSnapshot(snapshotImage::poll, snapshotImage::isEndOfStream);
    }

    void loadSnapshot(SnapshotFragmentSource snapshotSource, BooleanSupplier endOfStream) {
        SectionedCoreSnapshotCodec.RecoveryBuffer recovery = new SectionedCoreSnapshotCodec.RecoveryBuffer();
        FragmentHandler fragmentHandler = (buffer, offset, length, header) ->
                recovery.accept(buffer, offset, length);
        while (!endOfStream.getAsBoolean()) {
            int fragments = snapshotSource.poll(fragmentHandler, 10);
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
        if (responseSink == null && session.isClosing()) return;
        int length = CoreMessageCodec.encodedResponseLength(response);
        if (responseScratch.length < length) {
            responseScratch = new byte[length];
            responseBuffer.wrap(responseScratch);
        }
        CoreMessageCodec.encodeResponse(header, response, state.committedCoreSequence(), responseScratch);
        pendingResponses.offer(session, responseBuffer, length, System.nanoTime());
    }

    private static boolean retryableOffer(long result) {
        return result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION
                || result == Publication.NOT_CONNECTED;
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
