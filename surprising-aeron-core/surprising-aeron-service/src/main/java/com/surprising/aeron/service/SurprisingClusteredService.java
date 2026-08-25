package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

public final class SurprisingClusteredService implements ClusteredService {

    private static final int MAX_PENDING_EGRESS_PER_SESSION = 64;
    private static final long MATCHING_TIMER_DELAY_MS = 10;
    private static final long MATCHING_WATCHDOG_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final long EGRESS_DRAIN_TIMER_DELAY_MS = 1;
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 30;
    private static final long MATCHING_WAKEUP_CORRELATION_ID = Long.MAX_VALUE - 1;
    private static final long FIRST_EGRESS_DRAIN_CORRELATION_ID = Long.MIN_VALUE + 1;

    private final ProductLine productLine;
    private final AtomicReference<Cluster.Role> role = new AtomicReference<>();
    private CoreProbeState state;
    private Cluster cluster;
    private IdleStrategy idleStrategy;
    private final Map<Long, PendingEgress> pendingEgress = new HashMap<>();
    private final Set<Long> activeEgressSessions = new HashSet<>();
    private final Map<Long, Long> egressDrainTimers = new HashMap<>();
    private final Map<Long, ArrayDeque<PendingClient>> pendingClients = new HashMap<>();
    private long nextEgressDrainCorrelationId = FIRST_EGRESS_DRAIN_CORRELATION_ID;
    private boolean matchingWakeupScheduled;
    private long matchingWatchSequence;
    private long matchingWatchStartedNanos;
    private long snapshotFenceNotReadyCount;
    private long snapshotFenceTimeoutCount;

    public SurprisingClusteredService(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("product line is required");
        }
        this.productLine = productLine;
        this.state = new CoreProbeState(productLine);
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        pendingEgress.clear();
        activeEgressSessions.clear();
        egressDrainTimers.clear();
        pendingClients.clear();
        nextEgressDrainCorrelationId = FIRST_EGRESS_DRAIN_CORRELATION_ID;
        matchingWakeupScheduled = false;
        resetMatchingWatchdog();
        snapshotFenceNotReadyCount = 0;
        snapshotFenceTimeoutCount = 0;
        idleStrategy = cluster.idleStrategy();
        role.set(cluster.role());
        System.out.printf("Aeron core role productLine=%s role=%s%n", productLine, cluster.role());
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }
        schedulePendingMatchingTimers();
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
        boolean matcherPipelineCommand = request.header().kind()
                == com.surprising.aeron.protocol.WireMessageKind.COMMAND
                && CoreProbeState.isMatchingCommand(request.header().messageType())
                && !state.hasPendingMatchingForUser(request.header().userId());
        if (!matcherPipelineCommand) {
            completeEarlierMatching(timestamp, header.position());
        } else {
            state.drainMatchingCompletions();
        }
        var result = state.apply(request, timestamp, header.position());
        long matchingSequence = state.matchingSequence(request.header().commandId());
        if (matchingSequence > 0) {
            if (session != null) {
                pendingClients.computeIfAbsent(matchingSequence, ignored -> new ArrayDeque<>())
                        .addLast(new PendingClient(session, request.header()));
            }
            scheduleMatchingWakeup();
            return;
        }
        long querySequence = state.querySequence(request.header().commandId());
        if (querySequence != 0) {
            if (session != null) {
                pendingClients.computeIfAbsent(querySequence, ignored -> new ArrayDeque<>())
                        .addLast(new PendingClient(session, request.header()));
            }
            scheduleQueryTimer(querySequence);
            return;
        }
        if (session != null) {
            CoreMessage response = new CoreMessage(request.header().response(responseType(request.header())),
                    CoreProtocol.responsePayload(visible(result)));
            offer(session, response);
        }
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
                while (snapshotPublication.offer(buffer, offset, chunkLength) < 0) {
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
            state.beginSnapshot(snapshotId, deadlineNanos);
            return state.captureSnapshotSections(
                    cluster == null ? 0 : cluster.time(),
                    cluster == null ? 0 : cluster.logPosition(),
                    System.nanoTime());
        } catch (CoreProbeState.SnapshotNotReadyException notReady) {
            snapshotFenceNotReadyCount++;
            throw notReady;
        } catch (CoreProbeState.SnapshotFenceTimeoutException timeout) {
            snapshotFenceTimeoutCount++;
            throw timeout;
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
        role.set(newRole);
        resetMatchingWatchdog();
        System.out.printf("Aeron core role-change productLine=%s role=%s%n", productLine, newRole);
        schedulePendingMatchingTimers();
    }

    @Override
    public int doBackgroundWork(long nowNs) {
        int work = 0;
        Iterator<Long> sessions = activeEgressSessions.iterator();
        while (sessions.hasNext()) {
            PendingEgress egress = pendingEgress.get(sessions.next());
            if (egress == null || egress.session.isClosing()) {
                if (egress != null) egress.queue.clear();
                sessions.remove();
            }
        }
        return work;
    }

    @Override
    public void onTerminate(Cluster cluster) {
        pendingEgress.clear();
        activeEgressSessions.clear();
        egressDrainTimers.clear();
        pendingClients.clear();
        matchingWakeupScheduled = false;
        resetMatchingWatchdog();
        this.cluster = null;
        state.close();
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        pendingEgress.put(session.id(), new PendingEgress(session));
        schedulePendingMatchingTimers();
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        pendingEgress.remove(session.id());
        activeEgressSessions.remove(session.id());
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        Long egressSessionId = egressDrainTimers.remove(correlationId);
        if (egressSessionId != null) {
            PendingEgress egress = pendingEgress.get(egressSessionId);
            if (egress == null || egress.session.isClosing()) {
                activeEgressSessions.remove(egressSessionId);
                return;
            }
            egress.drainTimerScheduled = false;
            drain(egress);
            if (egress.queue.isEmpty()) {
                activeEgressSessions.remove(egressSessionId);
            } else {
                scheduleEgressDrain(egress);
            }
            return;
        }
        if (correlationId == MATCHING_WAKEUP_CORRELATION_ID) {
            matchingWakeupScheduled = false;
            state.drainMatchingCompletions();
            for (int completed = 0; completed < 64; completed++) {
                long sequence = state.firstPendingMatchingSequence();
                if (sequence == 0) break;
                var matchingResult = state.awaitMatchingResult(sequence);
                if (matchingResult == null) {
                    assertMatchingWatchdogHealthy(sequence);
                    break;
                }
                recordMatchingProgress(sequence);
                CoreResponse result = state.completeMatching(sequence, matchingResult, timestamp,
                        cluster == null ? 0 : cluster.logPosition());
                if (result == null) break;
                deliverMatchingResponse(sequence, result);
            }
            scheduleMatchingWakeup();
            return;
        }
        if (correlationId < 0) {
            CoreResponse queryResult = state.takeQueryResult(correlationId);
            if (queryResult == null) {
                scheduleQueryTimer(correlationId);
                return;
            }
            ArrayDeque<PendingClient> clients = pendingClients.remove(correlationId);
            if (clients != null) {
                for (PendingClient pendingClient : clients) {
                    if (pendingClient.session().isClosing()) continue;
                    CoreMessage response = new CoreMessage(pendingClient.requestHeader().response(
                            responseType(pendingClient.requestHeader())), CoreProtocol.responsePayload(
                            visible(queryResult)));
                    offer(pendingClient.session(), response);
                }
            }
            return;
        }
        var matchingResult = state.awaitMatchingResult(correlationId);
        if (matchingResult == null) {
            assertMatchingWatchdogHealthy(correlationId);
            scheduleMatchingWakeup();
            return;
        }
        recordMatchingProgress(correlationId);
        CoreResponse result = state.completeMatching(correlationId, matchingResult, timestamp,
                cluster == null ? 0 : cluster.logPosition());
        if (result == null) {
            scheduleMatchingWakeup();
            return;
        }
        deliverMatchingResponse(correlationId, result);
        schedulePendingMatchingTimers();
    }

    CoreProbeState state() {
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
        replaceState(CoreProbeState.fromSnapshot(productLine, snapshot));
    }

    private void replaceState(CoreProbeState restored) {
        state.close();
        state = restored;
    }

    @FunctionalInterface
    interface SnapshotFragmentSource {
        int poll(FragmentHandler fragmentHandler, int fragmentLimit);
    }

    private void offer(ClientSession session, CoreMessage message) {
        PendingEgress egress = pendingEgress.computeIfAbsent(session.id(), id -> new PendingEgress(session));
        int length = CoreMessageCodec.encodedLength(message);
        egress.ensureScratch(length);
        CoreMessageCodec.encode(message, egress.scratch);
        if (!egress.queue.isEmpty()) {
            enqueue(egress, egress.scratch, length);
            scheduleEgressDrain(egress);
            return;
        }
        if (session.offer(egress.scratchBuffer, 0, length) < 0) {
            enqueue(egress, egress.scratch, length);
            scheduleEgressDrain(egress);
        }
    }

    private static int drain(PendingEgress egress) {
        if (egress.session.isClosing()) {
            egress.queue.clear();
            return 0;
        }
        int work = 0;
        while (!egress.queue.isEmpty()) {
            UnsafeBuffer encoded = egress.queue.peekFirst();
            if (egress.session.offer(encoded, 0, encoded.capacity()) < 0) {
                break;
            }
            egress.queue.removeFirst();
            work++;
        }
        return work;
    }

    private void enqueue(PendingEgress egress, byte[] encoded, int length) {
        if (egress.queue.size() >= MAX_PENDING_EGRESS_PER_SESSION) {
            egress.queue.clear();
            egress.session.close();
            return;
        }
        egress.queue.addLast(new UnsafeBuffer(Arrays.copyOf(encoded, length)));
        activeEgressSessions.add(egress.session.id());
    }

    private void scheduleEgressDrain(PendingEgress egress) {
        if (cluster == null || egress.drainTimerScheduled || egress.session.isClosing()
                || egress.queue.isEmpty()) return;
        long correlationId = nextEgressDrainCorrelationId++;
        long delay = Math.max(1L, cluster.timeUnit().convert(EGRESS_DRAIN_TIMER_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        long deadline = cluster.time() + delay;
        idleStrategy.reset();
        while (!cluster.scheduleTimer(correlationId, deadline)) {
            idleStrategy.idle();
        }
        egressDrainTimers.put(correlationId, egress.session.id());
        egress.drainTimerScheduled = true;
    }

    private static final class PendingEgress {
        private final ClientSession session;
        private final ArrayDeque<UnsafeBuffer> queue = new ArrayDeque<>();
        private byte[] scratch = new byte[0];
        private UnsafeBuffer scratchBuffer = new UnsafeBuffer(scratch);
        private boolean drainTimerScheduled;

        private PendingEgress(ClientSession session) {
            this.session = session;
        }

        private void ensureScratch(int length) {
            if (scratch.length >= length) return;
            scratch = new byte[length];
            scratchBuffer = new UnsafeBuffer(scratch);
        }
    }

    private void schedulePendingMatchingTimers() {
        scheduleMatchingWakeup();
    }

    private void scheduleMatchingWakeup() {
        if (state.pendingMatchingCount() == 0) {
            resetMatchingWatchdog();
            return;
        }
        if (cluster == null || matchingWakeupScheduled) return;
        long delay = Math.max(1L, cluster.timeUnit().convert(MATCHING_TIMER_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        long deadline = cluster.time() + delay;
        idleStrategy.reset();
        while (!cluster.scheduleTimer(MATCHING_WAKEUP_CORRELATION_ID, deadline)) {
            idleStrategy.idle();
        }
        matchingWakeupScheduled = true;
    }

    private void assertMatchingWatchdogHealthy(long sequence) {
        long now = System.nanoTime();
        if (matchingWatchSequence != sequence) {
            matchingWatchSequence = sequence;
            matchingWatchStartedNanos = now;
            return;
        }
        if (role.get() == Cluster.Role.LEADER
                && now - matchingWatchStartedNanos >= MATCHING_WATCHDOG_TIMEOUT_NANOS) {
            throw new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                    "matching watchdog", sequence, 0, "matcher continuation exceeded local leader timeout");
        }
    }

    private void recordMatchingProgress(long sequence) {
        matchingWatchSequence = sequence;
        matchingWatchStartedNanos = System.nanoTime();
    }

    private void resetMatchingWatchdog() {
        matchingWatchSequence = 0;
        matchingWatchStartedNanos = 0;
    }

    private void deliverMatchingResponse(long sequence, CoreResponse result) {
        ArrayDeque<PendingClient> clients = pendingClients.remove(sequence);
        if (clients == null) return;
        for (PendingClient pendingClient : clients) {
            if (pendingClient.session().isClosing()) continue;
            CoreMessage response = new CoreMessage(pendingClient.requestHeader().response(
                    responseType(pendingClient.requestHeader())), CoreProtocol.responsePayload(visible(result)));
            offer(pendingClient.session(), response);
        }
    }

    private CoreResponse visible(CoreResponse result) {
        return result.withCommittedCoreSequence(state.committedCoreSequence());
    }

    private void completeEarlierMatching(long timestamp, long clusterPosition) {
        state.drainMatchingCompletions();
        while (true) {
            long sequence = state.firstPendingMatchingSequence();
            if (sequence == 0) {
                return;
            }
            if (state.hasPendingMatchingRejection(sequence)) {
                CoreResponse rejected = state.completeRejectedMatching(sequence);
                if (rejected != null) deliverMatchingResponse(sequence, rejected);
                continue;
            }
            var matchingResult = state.awaitMatchingResult(sequence);
            if (matchingResult == null) {
                throw new com.surprising.aeron.service.matching.FatalMatchingDivergenceException(
                        "matching command fence", sequence, 0, "pending matcher continuation is unavailable");
            }
            recordMatchingProgress(sequence);
            CoreResponse result = state.completeMatching(sequence, matchingResult, timestamp, clusterPosition);
            if (result != null) {
                deliverMatchingResponse(sequence, result);
            }
        }
    }

    private void scheduleQueryTimer(long sequence) {
        if (cluster == null || sequence == 0) return;
        long delay = Math.max(1L, cluster.timeUnit().convert(MATCHING_TIMER_DELAY_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS));
        long deadline = cluster.time() + delay;
        idleStrategy.reset();
        while (!cluster.scheduleTimer(sequence, deadline)) {
            idleStrategy.idle();
        }
    }

    private record PendingClient(ClientSession session,
                                 com.surprising.aeron.protocol.CoreMessageHeader requestHeader) {
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
            default -> requestHeader.kind() == WireMessageKind.QUERY
                    ? CoreMessageType.STATE_HASH_RESULT : CoreMessageType.COMMAND_RESULT;
        };
    }
}
