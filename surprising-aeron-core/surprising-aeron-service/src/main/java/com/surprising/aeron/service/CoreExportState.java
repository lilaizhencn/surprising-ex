package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.product.api.ProductLine;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

final class CoreExportState implements AutoCloseable {

    static final int MAX_PENDING_EVENTS = 1_000_000;
    static final long DEFAULT_PENDING_BYTES = 64L * 1024 * 1024;
    static final long MAX_PENDING_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_EVENT_BYTES = CoreProtocol.HEADER_LENGTH
            + (long) CoreMessageCodec.MAX_PAYLOAD_LENGTH;
    private static final long MATERIALIZATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private long acknowledgedSequence;
    private long nextSequence;
    private final ArrayDeque<PendingExport> pending;
    private long pendingBytes;
    private long pendingDigest;
    private final SpscTaskQueue<PendingExport> materializationQueue;
    private final Thread materializer;
    private final EventEncoder encoder;
    private final AtomicReference<Throwable> materializationFailure = new AtomicReference<>();
    private volatile long submittedMaterializations;
    private volatile long completedMaterializations;
    private final AtomicInteger acknowledgedMaterializationItems = new AtomicInteger();
    private final AtomicLong acknowledgedMaterializationBytes = new AtomicLong();
    private final int eventCapacity;
    private final long byteCapacity;
    private final int materializationBatchSize;
    private int reservedEvents;
    private long reservedBytes;
    private long maxBacklog;
    private volatile long materializationBatchCount;
    private volatile long materializationBatchItems;
    private volatile long materializationBatchBytes;
    private long rejectionCount;
    private volatile long errorCount;
    private long timeoutCount;
    private volatile boolean closed;
    private volatile boolean activated;
    private long nextMaterializationSequence;

    CoreExportState() {
        this(null, 0, 1, List.of(), null, CoreExportCodec::encodeEvent);
        activate();
    }

    CoreExportState(EventEncoder encoder) {
        this(null, 0, 1, List.of(), null, encoder);
        activate();
    }

    private CoreExportState(ProductLine expectedProductLine,
                            long acknowledgedSequence, long nextSequence, List<CoreMessage> pending,
                            List<Integer> restoredReservedLengths,
                            EventEncoder encoder) {
        if (acknowledgedSequence < 0 || pending == null || pending.size() > MAX_PENDING_EVENTS
                || restoredReservedLengths != null && restoredReservedLengths.size() != pending.size()) {
            throw new IllegalArgumentException("invalid export state");
        }
        if (nextSequence <= acknowledgedSequence
                || nextSequence - acknowledgedSequence - 1 != pending.size()) {
            throw new IllegalArgumentException("outbox next sequence does not match pending events");
        }
        this.acknowledgedSequence = acknowledgedSequence;
        this.nextSequence = nextSequence;
        this.pending = new ArrayDeque<>(pending.size());
        this.encoder = java.util.Objects.requireNonNull(encoder, "encoder");
        eventCapacity = configuredCapacity();
        byteCapacity = configuredByteCapacity();
        materializationBatchSize = configuredBatchSize(eventCapacity);
        materializationQueue = new SpscTaskQueue<>(eventCapacity);
        long expectedSequence = Math.incrementExact(acknowledgedSequence);
        int pendingIndex = 0;
        for (CoreMessage event : pending) {
            if (expectedProductLine == null) {
                throw new IllegalArgumentException("restored export product line is required");
            }
            CoreExportEvent decoded = CoreExportCodec.decodeEvent(event, expectedProductLine);
            validateRestoredCommandIdentity(event, decoded, expectedProductLine);
            if (event.header().sourceSequence() != expectedSequence
                    || decoded.exportSequence() != expectedSequence) {
                throw new IllegalArgumentException("non-contiguous export state");
            }
            int reservedLength = restoredReservedLengths == null
                    ? reservedEventLength(decoded) : restoredReservedLengths.get(pendingIndex++);
            PendingExport restored = pendingExport(event, decoded, reservedLength);
            this.pending.add(restored);
            pendingBytes = Math.addExact(pendingBytes, restored.encodedLength());
            pendingDigest ^= restored.digest();
            expectedSequence = Math.incrementExact(expectedSequence);
        }
        if (pendingBytes > byteCapacity || this.pending.size() > eventCapacity) {
            throw new IllegalArgumentException("export state exceeds byte limit");
        }
        maxBacklog = this.pending.size();
        nextMaterializationSequence = nextSequence;
        materializer = Thread.ofPlatform().daemon(true).name("core-fact-materializer")
                .unstarted(this::runMaterializer);
    }

    static CoreExportState passive() {
        return new CoreExportState(null, 0, 1, List.of(), null, CoreExportCodec::encodeEvent);
    }

    static CoreExportState restore(ProductLine expectedProductLine,
                                   long acknowledgedSequence, long nextSequence, List<CoreMessage> pending) {
        return new CoreExportState(Objects.requireNonNull(expectedProductLine, "expectedProductLine"),
                acknowledgedSequence, nextSequence, pending, null, CoreExportCodec::encodeEvent);
    }

    static CoreExportState restore(ProductLine expectedProductLine,
                                   long acknowledgedSequence, long nextSequence, List<CoreMessage> pending,
                                   List<Integer> reservedLengths) {
        return new CoreExportState(Objects.requireNonNull(expectedProductLine, "expectedProductLine"),
                acknowledgedSequence, nextSequence, pending, List.copyOf(reservedLengths),
                CoreExportCodec::encodeEvent);
    }

    void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed export state");
        activated = true;
        materializer.start();
    }

    boolean activated() { return activated; }

    private static void validateRestoredCommandIdentity(
            CoreMessage envelope, CoreExportEvent event, ProductLine expectedProductLine) {
        CoreMessageHeader header = envelope.header();
        CoreMessage canonicalCommand = new CoreMessage(new CoreMessageHeader(
                header.schemaVersion(), WireMessageKind.COMMAND, event.commandType(), event.commandId(),
                expectedProductLine, header.route(), header.source(), header.sourceId(), 0,
                event.userId(), header.submittedAtEpochMillis(), header.correlationId()),
                event.commandPayloadUnsafe());
        if (!CommandFingerprint.of(canonicalCommand).equals(event.commandFingerprint())) {
            throw new com.surprising.aeron.protocol.ProtocolException(
                    "Core export event command identity mismatch");
        }
    }

    long append(Draft draft) {
        if (draft == null) throw new IllegalArgumentException("core fact draft is required");
        AdmissionReservation reservation = reserveAdmission(1, reservedEventLength(draft));
        try {
            return append(reservation, draft);
        } finally {
            if (!reservation.closed) release(reservation);
        }
    }

    AdmissionReservation reserveAdmission(int events, long bytes) {
        assertHealthy();
        requireActivated();
        if (events < 1 || bytes < CoreProtocol.HEADER_LENGTH) {
            throw new IllegalArgumentException("invalid export admission request");
        }
        if (events > eventCapacity - pending.size() - acknowledgedMaterializationItems.get() - reservedEvents
                || bytes > byteCapacity - pendingBytes - acknowledgedMaterializationBytes.get() - reservedBytes) {
            rejectionCount++;
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached hard limit");
        }
        reservedEvents = Math.addExact(reservedEvents, events);
        reservedBytes = Math.addExact(reservedBytes, bytes);
        return new AdmissionReservation(this, events, bytes);
    }

    AdmissionReservation reserveAdmission(int events) {
        return reserveAdmission(events, Math.multiplyExact(MAX_EVENT_BYTES, events));
    }

    void release(AdmissionReservation reservation) {
        validateReservation(reservation);
        reservedEvents = Math.subtractExact(reservedEvents, reservation.remainingEvents);
        reservedBytes = Math.subtractExact(reservedBytes, reservation.remainingBytes);
        reservation.remainingEvents = 0;
        reservation.remainingBytes = 0;
        reservation.closed = true;
    }

    long append(AdmissionReservation reservation, Draft draft) {
        validateReservation(reservation);
        if (draft == null) throw new IllegalArgumentException("core fact draft is required");
        long sequence = nextSequence;
        int eventBytes = reservedEventLength(draft);
        if (eventBytes > reservation.remainingBytes) {
            throw new IllegalStateException("Core Fact exceeded aggregate admission byte reservation");
        }
        CoreMessageHeader header = draft.command().header().exportEvent(sequence);
        PendingExport appended = new PendingExport(draft, header, eventBytes,
                draftDigest(header, draft), draft.terminalOrderIds());
        pending.add(appended);
        pendingBytes = Math.addExact(pendingBytes, eventBytes);
        pendingDigest ^= appended.digest();
        nextSequence = Math.incrementExact(nextSequence);
        reservation.remainingEvents--;
        reservation.remainingBytes -= eventBytes;
        reservedEvents--;
        reservedBytes -= eventBytes;
        if (reservation.remainingEvents == 0) {
            reservedBytes -= reservation.remainingBytes;
            reservation.remainingBytes = 0;
            reservation.closed = true;
        }
        submittedMaterializations = Math.incrementExact(submittedMaterializations);
        if (!materializationQueue.offer(appended)) {
            IllegalStateException impossible = new IllegalStateException(
                    "reserved Core Fact materialization slot was unavailable");
            poison(impossible);
            submittedMaterializations--;
            appended.completeExceptionally(impossible, this);
            throw impossible;
        }
        maxBacklog = Math.max(maxBacklog, pending.size() + acknowledgedMaterializationItems.get());
        return sequence;
    }

    void beginMaterializationBatch() {
        assertHealthy();
        materializationQueue.beginBatch();
    }

    void endMaterializationBatch() {
        materializationQueue.endBatch();
    }

    boolean hasCapacity() {
        assertHealthy();
        return hasCapacityFor(1);
    }

    boolean hasCapacityFor() {
        return hasCapacityFor(1);
    }

    boolean hasCapacityFor(int additionalEvents) {
        assertHealthy();
        if (additionalEvents < 1 || pending.size() + acknowledgedMaterializationItems.get()
                > eventCapacity - additionalEvents - reservedEvents) {
            return false;
        }
        return pendingBytes + acknowledgedMaterializationBytes.get()
                <= byteCapacity - reservedBytes - Math.multiplyExact(MAX_EVENT_BYTES, additionalEvents);
    }

    List<Long> acknowledge(AckExportCommand command) {
        assertHealthy();
        requireActivated();
        if (command.throughSequence() <= acknowledgedSequence) {
            return List.of();
        }
        long highestPending = Math.subtractExact(nextSequence, 1);
        if (command.throughSequence() > highestPending) {
            throw new CoreStateRejectedException("EXPORT_ACK_AHEAD", "export ack exceeds emitted sequence");
        }
        int removeCount = Math.toIntExact(command.throughSequence() - acknowledgedSequence);
        Set<Long> terminalOrderIds = new LinkedHashSet<>();
        for (int index = 0; index < removeCount; index++) {
            PendingExport removed = pending.removeFirst();
            for (long orderId : removed.terminalOrderIds()) terminalOrderIds.add(orderId);
            if (!removed.ready()) retainAcknowledgedMaterialization(removed);
            pendingBytes = Math.subtractExact(pendingBytes, removed.encodedLength());
            pendingDigest ^= removed.digest();
        }
        acknowledgedSequence = command.throughSequence();
        return List.copyOf(terminalOrderIds);
    }

    List<CoreMessage> batch(int maxEvents) {
        assertHealthy();
        requireActivated();
        int count = 0;
        long encodedLength = Integer.BYTES;
        int limit = Math.min(maxEvents, pending.size());
        ArrayList<CoreMessage> batch = new ArrayList<>(limit);
        Iterator<PendingExport> iterator = pending.iterator();
        while (count < limit && iterator.hasNext()) {
            PendingExport pendingEvent = iterator.next();
            if (!pendingEvent.ready()) break;
            CoreMessage event = pendingEvent.message();
            int eventLength = encodedLength(event);
            long nextLength = Math.addExact(encodedLength, Math.addExact(Integer.BYTES, eventLength));
            if (nextLength > CoreExportCodec.MAX_BATCH_ENCODED_LENGTH - CoreExportCodec.BATCH_STATUS_FIXED_LENGTH) {
                break;
            }
            encodedLength = nextLength;
            batch.add(event);
            count++;
        }
        return List.copyOf(batch);
    }

    CoreExportStatus status() {
        assertHealthy();
        return new CoreExportStatus(acknowledgedSequence, nextSequence, pending.size(), pendingBytes,
                eventCapacity, byteCapacity);
    }

    long acknowledgedSequence() {
        return acknowledgedSequence;
    }

    long nextSequence() {
        return nextSequence;
    }

    List<CoreMessage> pending() {
        assertHealthy();
        ArrayList<CoreMessage> events = new ArrayList<>(pending.size());
        for (PendingExport event : pending) events.add(event.message());
        return List.copyOf(events);
    }

    Iterable<CoreMessage> pendingEvents() {
        return pending()::iterator;
    }

    int pendingCount() {
        return pending.size();
    }

    long pendingDigest() {
        return pendingDigest;
    }

    int encodedPendingCount() {
        int count = 0;
        for (PendingExport event : pending) {
            if (event.encoded()) count++;
        }
        return count;
    }

    long materializedThroughSequence() {
        long sequence = acknowledgedSequence;
        for (PendingExport event : pending) {
            if (!event.ready()) break;
            sequence++;
        }
        return sequence;
    }

    @Override
    public void close() {
        closed = true;
        materializationQueue.signalConsumer();
        if (!activated) return;
        boolean interrupted = false;
        long deadline = System.nanoTime() + MATERIALIZATION_TIMEOUT_NANOS;
        while (materializer.isAlive() && System.nanoTime() < deadline) {
            try {
                materializer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (materializer.isAlive()) {
            timeoutCount++;
            TimeoutException timeout = new TimeoutException("Core Fact materializer close timed out");
            poison(timeout);
            materializer.interrupt();
            failPendingMaterializations(timeout);
            throw new IllegalStateException("Core Fact materializer did not terminate", timeout);
        }
        Throwable failure = materializationFailure.get();
        if (failure != null) {
            throw new CompletionException("Core Fact materializer closed after failure", failure);
        }
        if (materializationBacklog() != 0 || !materializationQueue.isEmpty()
                || acknowledgedMaterializationItems.get() != 0
                || acknowledgedMaterializationBytes.get() != 0) {
            throw new IllegalStateException("Core Fact materializer closed before draining");
        }
    }

    private void retainAcknowledgedMaterialization(PendingExport removed) {
        long bytes = removed.encodedLength();
        acknowledgedMaterializationItems.incrementAndGet();
        acknowledgedMaterializationBytes.addAndGet(bytes);
        if (!removed.retainAcknowledged()) releaseAcknowledgedMaterialization(bytes);
    }

    private void releaseAcknowledgedMaterialization(long bytes) {
        acknowledgedMaterializationItems.decrementAndGet();
        acknowledgedMaterializationBytes.addAndGet(-bytes);
    }

    Snapshot snapshot() {
        assertHealthy();
        try {
            ArrayList<Integer> reservedLengths = new ArrayList<>(pending.size());
            for (PendingExport value : pending) reservedLengths.add(value.encodedLength());
            return new Snapshot(acknowledgedSequence, nextSequence, pending(), reservedLengths, pendingDigest);
        } catch (RuntimeException failure) {
            poison(failure);
            throw failure;
        }
    }

    record Snapshot(long acknowledgedSequence, long nextSequence, List<CoreMessage> pendingEvents,
                    List<Integer> pendingReservedLengths,
                    long pendingDigest) {
        Snapshot {
            pendingEvents = List.copyOf(pendingEvents);
            pendingReservedLengths = List.copyOf(pendingReservedLengths);
            if (acknowledgedSequence < 0 || nextSequence <= acknowledgedSequence
                    || nextSequence - acknowledgedSequence - 1 != pendingEvents.size()
                    || pendingReservedLengths.size() != pendingEvents.size()) {
                throw new IllegalArgumentException("invalid export snapshot");
            }
        }

        int pendingCount() {
            return pendingEvents.size();
        }
    }

    private static int encodedLength(CoreMessage message) {
        return Math.addExact(CoreProtocol.HEADER_LENGTH, message.payloadLength());
    }

    private static long eventDigest(CoreMessageHeader header, CoreExportEvent event) {
        return metadataDigest(header, event.appliedCommandCount(), event.businessStateHash(),
                event.commandType().wireCode(), event.commandStatus().wireCode(), event.resultCode().wireCode(),
                event.beforeBusinessStateHash(), event.beforeFundsStateHash(), event.fundsStateHash(),
                event.topologyHash(), event.laneRevisionHash(), event.committedCoreSequence(),
                event.matcherTransition(), event.clusterPosition(), event.commandPayloadUnsafe());
    }

    private static long draftDigest(CoreMessageHeader header, Draft draft) {
        return metadataDigest(header, draft.appliedCommandCount(), draft.businessStateHash(),
                draft.command().header().messageType().wireCode(), draft.status().wireCode(),
                draft.resultCode().wireCode(), draft.beforeBusinessStateHash(), draft.beforeFundsStateHash(),
                draft.fundsStateHash(), draft.topologyHash(), draft.laneRevisionHash(), draft.appliedCommandCount(),
                draft.matcherTransition(), draft.clusterPosition(), draft.command().payloadUnsafe());
    }

    private static long metadataDigest(CoreMessageHeader header, long appliedCommandCount,
                                       long businessStateHash, int commandType, int commandStatus,
                                       int resultCode, long beforeBusinessStateHash, long beforeFundsStateHash,
                                       long fundsStateHash, long topologyHash, long laneRevisionHash,
                                       long committedCoreSequence, CoreMatcherTransition matcherTransition,
                                       long clusterPosition, byte[] commandPayload) {
        long hash = 0xcbf29ce484222325L;
        hash = digestLong(hash, header.schemaVersion());
        hash = digestLong(hash, header.kind().wireCode());
        hash = digestLong(hash, header.messageType().wireCode());
        hash = digestLong(hash, header.commandId().getMostSignificantBits());
        hash = digestLong(hash, header.commandId().getLeastSignificantBits());
        hash = digestLong(hash, header.productLine().ordinal());
        hash = digestLong(hash, header.source().wireCode());
        hash = digestLong(hash, header.sourceId());
        hash = digestLong(hash, header.sourceSequence());
        hash = digestLong(hash, header.userId());
        hash = digestLong(hash, header.submittedAtEpochMillis());
        hash = digestLong(hash, header.correlationId());
        hash = digestLong(hash, appliedCommandCount);
        hash = digestLong(hash, businessStateHash);
        hash = digestLong(hash, commandType);
        hash = digestLong(hash, commandStatus);
        hash = digestLong(hash, resultCode);
        hash = digestLong(hash, beforeBusinessStateHash);
        hash = digestLong(hash, beforeFundsStateHash);
        hash = digestLong(hash, fundsStateHash);
        hash = digestLong(hash, topologyHash);
        hash = digestLong(hash, laneRevisionHash);
        hash = digestLong(hash, committedCoreSequence);
        hash = digestLong(hash, matcherTransition.matcherShardId());
        hash = digestLong(hash, matcherTransition.sequenceBefore());
        hash = digestLong(hash, matcherTransition.sequenceAfter());
        hash = digestLong(hash, matcherTransition.prefixBefore());
        hash = digestLong(hash, matcherTransition.prefixAfter());
        hash = digestLong(hash, clusterPosition);
        for (byte value : commandPayload) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long digestLong(long hash, long value) {
        long result = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            result ^= (value >>> shift) & 0xff;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long[] terminalOrderIds(CoreExportEvent event) {
        return event.terminalIds().orderIds().stream().mapToLong(Long::longValue).toArray();
    }

    private static PendingExport pendingExport(CoreMessage message, CoreExportEvent event, int reservedLength) {
        if (reservedLength < encodedLength(message) || reservedLength > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("invalid restored export reservation length");
        }
        MaterializedExport materialized = new MaterializedExport(message, encodedLength(message));
        return new PendingExport(materialized, reservedLength,
                eventDigest(message.header(), event), terminalOrderIds(event));
    }

    private static final class PendingExport {
        private static final int READY = 1;
        private static final int FAILED = 1 << 1;
        private static final int RETAINED_AFTER_ACK = 1 << 2;
        private static final AtomicIntegerFieldUpdater<PendingExport> STATE =
                AtomicIntegerFieldUpdater.newUpdater(PendingExport.class, "completionState");
        private final Draft draft;
        private final CoreMessageHeader header;
        private final int encodedLength;
        private final long digest;
        private final long[] terminalOrderIds;
        @SuppressWarnings("unused")
        private volatile int completionState;
        private volatile MaterializedExport materialized;
        private volatile Throwable failure;
        private volatile Thread waiter;

        private PendingExport(Draft draft, CoreMessageHeader header,
                              int encodedLength, long digest, long[] terminalOrderIds) {
            this.draft = Objects.requireNonNull(draft, "draft");
            this.header = Objects.requireNonNull(header, "header");
            this.encodedLength = encodedLength;
            this.digest = digest;
            this.terminalOrderIds = terminalOrderIds.clone();
            if (encodedLength < CoreProtocol.HEADER_LENGTH) {
                throw new IllegalArgumentException("invalid pending export length");
            }
        }

        private PendingExport(MaterializedExport materialized,
                              int encodedLength, long digest, long[] terminalOrderIds) {
            this.draft = null;
            this.header = materialized.message().header();
            this.encodedLength = encodedLength;
            this.digest = digest;
            this.terminalOrderIds = terminalOrderIds.clone();
            this.materialized = Objects.requireNonNull(materialized, "materialized");
            completionState = READY;
        }

        private CoreMessage message() {
            long deadline = System.nanoTime() + MATERIALIZATION_TIMEOUT_NANOS;
            Thread current = Thread.currentThread();
            waiter = current;
            try {
                while (true) {
                    int state = completionState;
                    if ((state & READY) != 0) return materialized.message();
                    if ((state & FAILED) != 0) {
                        throw new CompletionException("Core Fact materialization failed", failure);
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new CompletionException("Core Fact materialization timed out",
                                new TimeoutException("Core Fact materialization timed out"));
                    }
                    LockSupport.parkNanos(this, remaining);
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException("Core Fact materialization interrupted",
                                new InterruptedException());
                    }
                }
            } finally {
                if (waiter == current) waiter = null;
            }
        }

        private boolean complete(MaterializedExport result, CoreExportState owner) {
            materialized = Objects.requireNonNull(result, "materialized export");
            return finish(READY, owner);
        }

        private boolean completeExceptionally(Throwable cause, CoreExportState owner) {
            failure = Objects.requireNonNull(cause, "materialization failure");
            return finish(FAILED, owner);
        }

        private boolean finish(int terminalState, CoreExportState owner) {
            while (true) {
                int state = completionState;
                if ((state & (READY | FAILED)) != 0) return false;
                if (!STATE.compareAndSet(this, state, state | terminalState)) continue;
                Thread blocked = waiter;
                if (blocked != null) LockSupport.unpark(blocked);
                if ((state & RETAINED_AFTER_ACK) != 0) {
                    owner.releaseAcknowledgedMaterialization(encodedLength);
                }
                return true;
            }
        }

        private boolean retainAcknowledged() {
            while (true) {
                int state = completionState;
                if ((state & (READY | FAILED)) != 0) return false;
                if ((state & RETAINED_AFTER_ACK) != 0) return true;
                if (STATE.compareAndSet(this, state, state | RETAINED_AFTER_ACK)) return true;
            }
        }

        private int encodedLength() { return encodedLength; }
        private long digest() { return digest; }
        private long[] terminalOrderIds() { return terminalOrderIds; }
        private boolean encoded() { return (completionState & READY) != 0; }
        private boolean ready() { return (completionState & (READY | FAILED)) != 0; }
    }

    record Draft(CoreMessage command, ResponseStatus status,
                 com.surprising.aeron.protocol.CoreResultCode resultCode,
                 long appliedCommandCount, long businessStateHash,
                 long beforeBusinessStateHash, long beforeFundsStateHash, long fundsStateHash,
                 long topologyHash, long laneRevisionHash, CoreMatcherTransition matcherTransition,
                 long clusterPosition, long projectionSequence, int itemCount, long[] terminalOrderIds,
                 FactChain patches, CoreCommandDelta delta,
                 com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta,
                 RuntimeFactFrame.IdentityView fallbackFundIdentities,
                 RuntimeFactFrame.CoreFactMetadata commandMetadata) {
        Draft(CoreMessage command, ResponseStatus status,
              com.surprising.aeron.protocol.CoreResultCode resultCode,
              long appliedCommandCount, long businessStateHash,
              long beforeBusinessStateHash, long beforeFundsStateHash, long fundsStateHash,
              long topologyHash, long laneRevisionHash, CoreMatcherTransition matcherTransition,
              long clusterPosition, long projectionSequence, int itemCount, long[] terminalOrderIds,
              FactChain patches, CoreCommandDelta delta,
              com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta,
              RuntimeFactFrame.CoreFactMetadata commandMetadata) {
            this(command, status, resultCode, appliedCommandCount, businessStateHash,
                    beforeBusinessStateHash, beforeFundsStateHash, fundsStateHash,
                    topologyHash, laneRevisionHash, matcherTransition, clusterPosition, projectionSequence,
                    itemCount, terminalOrderIds, patches, delta, fundsDelta, null, commandMetadata);
        }

        Draft {
            if (command == null || status == null || resultCode == null || appliedCommandCount < 0
                    || matcherTransition == null || clusterPosition < 0 || projectionSequence < 0
                    || itemCount < 0 || terminalOrderIds == null || delta == null || fundsDelta == null
                    || commandMetadata == null
                    || !command.header().commandId().equals(commandMetadata.commandId())
                    || command.header().messageType().wireCode() != commandMetadata.messageTypeWireCode()
                    || command.header().userId() != commandMetadata.userId()
                    || status != commandMetadata.status() || resultCode != commandMetadata.resultCode()
                    || appliedCommandCount != commandMetadata.appliedCommandCount()
                    || clusterPosition != commandMetadata.clusterPosition()
                    || topologyHash != commandMetadata.topologyHash()
                    || laneRevisionHash != commandMetadata.laneRevisionHash()) {
                throw new IllegalArgumentException("invalid Core Fact draft");
            }
            terminalOrderIds = terminalOrderIds.clone();
        }

        private CoreExportEvent materialize(long sequence) {
            RuntimeFactFrame first = patches == null ? null : patches.first();
            RuntimeFactFrame last = patches == null ? null : patches.patch();
            RuntimeFactFrame.FactIdentitySlice identities = patches == null
                    ? new RuntimeFactFrame.FactIdentitySlice(List.of(), List.of(), List.of(), List.of())
                    : patches.size() == 1 ? last.identities() : patches.identities();
            List<com.surprising.aeron.protocol.CoreUserStateView> users;
            List<com.surprising.aeron.protocol.CoreOrderStateView> orders;
            List<com.surprising.aeron.protocol.CoreLiquidationView> liquidations;
            List<com.surprising.aeron.protocol.CoreTreasuryAssetView> treasury;
            List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> triggers;
            List<RuntimeFactFrame.MatcherEvidence> evidence;
            RuntimeFactFrame.TerminalIds terminalIds;
            CoreExportEvent.Tombstones tombstones;
            if (patches != null && patches.size() == 1) {
                RuntimeFactFrame.CoreFactFragment fragment = last.materializeCoreFactFragment();
                users = fragment.changedUsers();
                orders = fragment.changedOrders();
                liquidations = fragment.changedLiquidations();
                treasury = fragment.changedTreasuryAssets();
                triggers = fragment.changedTriggerOrders();
                evidence = fragment.matcherEvidence();
                terminalIds = mergeTerminalOrders(fragment.terminalIds(), terminalOrderIds);
                tombstones = fragment.tombstones();
            } else {
                FactViewMerge merged = new FactViewMerge();
                if (patches != null) {
                    patches.acceptOldestFirst(patch -> merged.accept(patch.materializeCoreFactFragment()));
                }
                for (long orderId : terminalOrderIds) merged.terminalOrders.add(orderId);
                users = List.copyOf(merged.users.values());
                orders = List.copyOf(merged.orders.values());
                liquidations = List.copyOf(merged.liquidations.values());
                treasury = List.copyOf(merged.treasury.values());
                triggers = List.copyOf(merged.triggers.values());
                evidence = merged.evidence;
                terminalIds = new RuntimeFactFrame.TerminalIds(List.copyOf(merged.terminalOrders),
                        List.copyOf(merged.terminalLiquidations), List.copyOf(merged.terminalTriggers));
                tombstones = merged.tombstones.seal();
            }
            long previousCoreSequence = first == null ? appliedCommandCount - 1 : first.previousCoreSequence();
            long coreSequence = last == null ? appliedCommandCount : last.coreSequence();
            long previousProjectionSequence = first == null ? projectionSequence : first.previousProjectionSequence();
            long committedProjectionSequence = last == null ? projectionSequence : last.projectionSequence();
            List<com.surprising.aeron.protocol.CoreFundsPostingView> fundsPostings =
                    fundsDelta.materialize(identities, fallbackFundIdentities,
                            commandMetadata.externalAdjustment()).views();
            List<CoreExportEvent.MatcherEvidence> matcherEvidence = materializeMatcherEvidence(evidence);
            return new CoreExportEvent(sequence, appliedCommandCount, businessStateHash,
                    command.header().commandId(), command.header().messageType(), status, resultCode,
                    command.header().userId(), command.payloadUnsafe(), users,
                    orders, delta.executions(), delta.fundingPayments(),
                    liquidations, treasury, triggers,
                    beforeBusinessStateHash, beforeFundsStateHash, fundsStateHash,
                    matcherTransition.routeVersion(), topologyHash, laneRevisionHash, appliedCommandCount,
                    matcherTransition, clusterPosition, fundsPostings,
                    commandMetadata.commandFingerprint(), matcherEvidence,
                    new CoreExportEvent.TerminalIds(terminalIds.orderIds(),
                            terminalIds.liquidationIds(), terminalIds.triggerOrderIds()),
                    previousCoreSequence, coreSequence, previousProjectionSequence,
                    committedProjectionSequence, delta.fundingProgress(), delta.settlementProgress(),
                    tombstones);
        }

        private static RuntimeFactFrame.TerminalIds mergeTerminalOrders(
                RuntimeFactFrame.TerminalIds terminalIds, long[] additionalOrderIds) {
            if (additionalOrderIds.length == 0) return terminalIds;
            ArrayList<Long> orders = new ArrayList<>(terminalIds.orderIds());
            for (long orderId : additionalOrderIds) {
                boolean present = false;
                for (long existing : orders) {
                    if (existing == orderId) {
                        present = true;
                        break;
                    }
                }
                if (!present) orders.add(orderId);
            }
            orders.sort(Long::compare);
            return new RuntimeFactFrame.TerminalIds(List.copyOf(orders), terminalIds.liquidationIds(),
                    terminalIds.triggerOrderIds());
        }

        private static List<CoreExportEvent.MatcherEvidence> materializeMatcherEvidence(
                List<RuntimeFactFrame.MatcherEvidence> evidence) {
            ArrayList<CoreExportEvent.MatcherEvidence> result = new ArrayList<>(evidence.size());
            for (RuntimeFactFrame.MatcherEvidence item : evidence) {
                result.add(new CoreExportEvent.MatcherEvidence(item.matcherSequence(), item.matcherShardId(),
                        item.makerOrderId(), item.takerOrderId(), item.quantitySteps(), item.priceTicks()));
            }
            return result;
        }
    }

    static final class FactChain {
        private final com.surprising.aeron.service.state.TradingRuntimeState.PreparedFactFrame frame;
        private final FactChain previous;
        private final int size;
        private final CoreAdmissionReservation.FactPermit permit;
        private RuntimeFactFrame materialized;

        FactChain(com.surprising.aeron.service.state.TradingRuntimeState.PreparedFactFrame frame,
                   FactChain previous, CoreAdmissionReservation.FactPermit permit) {
            this.frame = Objects.requireNonNull(frame, "fact frame");
            this.permit = Objects.requireNonNull(permit, "permit");
            this.previous = previous;
            if (previous != null && (!previous.permit.sameOwner(permit)
                    || permit.ordinal() != previous.permit.ordinal() + 1)) {
                throw new IllegalArgumentException("foreign, missing, or reordered fact permit");
            }
            if (previous != null && previous.frame.sequence() + 1 != frame.sequence()) {
                throw new IllegalArgumentException("non-contiguous fact frame chain");
            }
            permit.requireConsumed();
            size = Math.addExact(previous == null ? 0 : previous.size, 1);
        }

        FactChain(RuntimeFactFrame materialized, FactChain previous,
                   CoreAdmissionReservation.FactPermit permit) {
            this.frame = null;
            this.materialized = Objects.requireNonNull(materialized, "fact frame");
            this.permit = Objects.requireNonNull(permit, "permit");
            this.previous = previous;
            if (previous != null && (!previous.permit.sameOwner(permit)
                    || permit.ordinal() != previous.permit.ordinal() + 1)) {
                throw new IllegalArgumentException("foreign, missing, or reordered fact permit");
            }
            if (previous != null && previous.sequence() + 1 != materialized.sequence()) {
                throw new IllegalArgumentException("non-contiguous fact frame chain");
            }
            permit.requireConsumed();
            size = Math.addExact(previous == null ? 0 : previous.size, 1);
        }

        private long sequence() { return frame == null ? materialized.sequence() : frame.sequence(); }

        RuntimeFactFrame patch() {
            if (materialized == null) materialized = frame.materialize();
            return materialized;
        }
        RuntimeFactFrame.CoreFactMetadata coreFactMetadata() {
            return frame == null ? materialized.coreFactMetadata() : frame.coreFactMetadata();
        }
        int size() { return size; }
        int itemCount() {
            int current = frame == null ? materialized.coreFactItemCount() : frame.coreFactItemCount();
            return previous == null ? current : Math.addExact(previous.itemCount(), current);
        }

        RuntimeFactFrame first() {
            FactChain cursor = this;
            while (cursor.previous != null) cursor = cursor.previous;
            return cursor.patch();
        }

        void acceptOldestFirst(java.util.function.Consumer<RuntimeFactFrame> consumer) {
            if (previous != null) previous.acceptOldestFirst(consumer);
            consumer.accept(patch());
        }

        void acceptPreparedOldestFirst(
                java.util.function.Consumer<com.surprising.aeron.service.state.TradingRuntimeState.PreparedFactFrame>
                        consumer) {
            if (previous != null) previous.acceptPreparedOldestFirst(consumer);
            if (frame != null) consumer.accept(frame);
        }

        void retainOldestFirst(TerminalStateRetention retention, long exportSequence) {
            if (previous != null) previous.retainOldestFirst(retention, exportSequence);
            if (frame != null) retention.observe(frame, exportSequence);
            else retention.observe(materialized, exportSequence);
        }

        RuntimeFactFrame.FactIdentitySlice identities() {
            RuntimeFactFrame.FactIdentitySlice[] merged = {
                    new RuntimeFactFrame.FactIdentitySlice(List.of(), List.of(), List.of(), List.of())};
            acceptOldestFirst(value -> merged[0] = merged[0].merge(value.identities()));
            return merged[0];
        }
    }

    private static com.surprising.aeron.protocol.CoreUserStateView mergeFactUser(
            com.surprising.aeron.protocol.CoreUserStateView previous,
            com.surprising.aeron.protocol.CoreUserStateView current) {
        LinkedHashMap<String, com.surprising.aeron.protocol.CoreBalanceView> balances = new LinkedHashMap<>();
        previous.balances().forEach(value -> balances.put(value.asset(), value));
        current.balances().forEach(value -> balances.put(value.asset(), value));
        LinkedHashMap<Long, com.surprising.aeron.protocol.CoreReservationView> reservations = new LinkedHashMap<>();
        previous.reservations().forEach(value -> reservations.put(value.orderId(), value));
        current.reservations().forEach(value -> reservations.put(value.orderId(), value));
        LinkedHashMap<PositionKey, com.surprising.aeron.protocol.CorePositionView> positions = new LinkedHashMap<>();
        previous.positions().forEach(value -> positions.put(
                new PositionKey(value.symbol(), value.positionSide()), value));
        current.positions().forEach(value -> positions.put(
                new PositionKey(value.symbol(), value.positionSide()), value));
        LinkedHashMap<LeverageKey, com.surprising.aeron.protocol.CoreLeverageView> leverages = new LinkedHashMap<>();
        previous.leverages().forEach(value -> leverages.put(
                new LeverageKey(value.symbol(), value.marginMode()), value));
        current.leverages().forEach(value -> leverages.put(
                new LeverageKey(value.symbol(), value.marginMode()), value));
        return new com.surprising.aeron.protocol.CoreUserStateView(current.productLine(), current.userId(),
                current.revision(), current.positionMode(), List.copyOf(balances.values()),
                List.copyOf(reservations.values()), List.copyOf(positions.values()),
                List.copyOf(leverages.values()));
    }

    private static final class FactViewMerge {
        private final LinkedHashMap<Long, com.surprising.aeron.protocol.CoreUserStateView> users =
                new LinkedHashMap<>();
        private final LinkedHashMap<Long, com.surprising.aeron.protocol.CoreOrderStateView> orders =
                new LinkedHashMap<>();
        private final LinkedHashMap<Long, com.surprising.aeron.protocol.CoreLiquidationView> liquidations =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, com.surprising.aeron.protocol.CoreTreasuryAssetView> treasury =
                new LinkedHashMap<>();
        private final LinkedHashMap<Long, com.surprising.aeron.protocol.CoreTriggerOrderStateView> triggers =
                new LinkedHashMap<>();
        private final ArrayList<RuntimeFactFrame.MatcherEvidence> evidence = new ArrayList<>();
        private final LinkedHashSet<Long> terminalOrders = new LinkedHashSet<>();
        private final LinkedHashSet<Long> terminalLiquidations = new LinkedHashSet<>();
        private final LinkedHashSet<Long> terminalTriggers = new LinkedHashSet<>();
        private final FactTombstoneMerge tombstones = new FactTombstoneMerge();

        private void accept(RuntimeFactFrame.CoreFactFragment value) {
            value.changedUsers().forEach(user -> users.merge(user.userId(), user,
                    CoreExportState::mergeFactUser));
            value.changedOrders().forEach(order -> orders.put(order.orderId(), order));
            value.changedLiquidations().forEach(item -> liquidations.put(item.liquidationId(), item));
            value.changedTreasuryAssets().forEach(item -> treasury.put(item.asset(), item));
            value.changedTriggerOrders().forEach(item -> triggers.put(item.triggerOrderId(), item));
            tombstones.observeValues(value);
            tombstones.apply(value.tombstones(), users, orders, liquidations, treasury, triggers);
            evidence.addAll(value.matcherEvidence());
            terminalOrders.addAll(value.terminalIds().orderIds());
            terminalLiquidations.addAll(value.terminalIds().liquidationIds());
            terminalTriggers.addAll(value.terminalIds().triggerOrderIds());
        }
    }

    private static final class FactTombstoneMerge {
        private final LinkedHashSet<Long> users = new LinkedHashSet<>();
        private final LinkedHashMap<AssetKey, CoreExportEvent.UserAssetKey> balances = new LinkedHashMap<>();
        private final LinkedHashMap<ReservationKey, CoreExportEvent.UserOrderKey> reservations =
                new LinkedHashMap<>();
        private final LinkedHashSet<Long> orders = new LinkedHashSet<>();
        private final LinkedHashMap<UserPositionKey, CoreExportEvent.UserPositionKey> positions =
                new LinkedHashMap<>();
        private final LinkedHashMap<UserLeverageKey, CoreExportEvent.UserLeverageKey> leverages =
                new LinkedHashMap<>();
        private final LinkedHashSet<Long> liquidations = new LinkedHashSet<>();
        private final LinkedHashSet<Long> algos = new LinkedHashSet<>();
        private final LinkedHashSet<Long> triggers = new LinkedHashSet<>();
        private final LinkedHashSet<String> treasury = new LinkedHashSet<>();

        private void observeValues(RuntimeFactFrame.CoreFactFragment fragment) {
            fragment.changedUsers().forEach(user -> {
                users.remove(user.userId());
                user.balances().forEach(value -> balances.remove(new AssetKey(user.userId(), value.asset())));
                user.reservations().forEach(value -> reservations.remove(
                        new ReservationKey(user.userId(), value.orderId())));
                user.positions().forEach(value -> positions.remove(
                        new UserPositionKey(user.userId(), value.symbol(), value.positionSide())));
                user.leverages().forEach(value -> leverages.remove(
                        new UserLeverageKey(user.userId(), value.symbol(), value.marginMode())));
            });
            fragment.changedOrders().forEach(value -> orders.remove(value.orderId()));
            fragment.changedLiquidations().forEach(value -> liquidations.remove(value.liquidationId()));
            fragment.changedTriggerOrders().forEach(value -> triggers.remove(value.triggerOrderId()));
            fragment.changedTreasuryAssets().forEach(value -> treasury.remove(value.asset()));
        }

        private void apply(CoreExportEvent.Tombstones deleted,
                           LinkedHashMap<Long, com.surprising.aeron.protocol.CoreUserStateView> changedUsers,
                           LinkedHashMap<Long, com.surprising.aeron.protocol.CoreOrderStateView> changedOrders,
                           LinkedHashMap<Long, com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations,
                           LinkedHashMap<String, com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasury,
                           LinkedHashMap<Long, com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggers) {
            deleted.userIds().forEach(userId -> {
                users.add(userId);
                changedUsers.remove(userId);
                balances.keySet().removeIf(key -> key.userId == userId);
                reservations.keySet().removeIf(key -> key.userId == userId);
                positions.keySet().removeIf(key -> key.userId == userId);
                leverages.keySet().removeIf(key -> key.userId == userId);
            });
            deleted.balances().forEach(key -> {
                balances.put(new AssetKey(key.userId(), key.asset()), key);
                changedUsers.computeIfPresent(key.userId(), (ignored, user) -> withoutBalance(user, key.asset()));
            });
            deleted.reservations().forEach(key -> {
                reservations.put(new ReservationKey(key.userId(), key.orderId()), key);
                changedUsers.computeIfPresent(key.userId(),
                        (ignored, user) -> withoutReservation(user, key.orderId()));
            });
            deleted.orderIds().forEach(orderId -> { orders.add(orderId); changedOrders.remove(orderId); });
            deleted.positions().forEach(key -> {
                positions.put(new UserPositionKey(key.userId(), key.symbol(), key.positionSide()), key);
                changedUsers.computeIfPresent(key.userId(), (ignored, user) -> withoutPosition(user, key));
            });
            deleted.leverages().forEach(key -> {
                leverages.put(new UserLeverageKey(key.userId(), key.symbol(), key.marginMode()), key);
                changedUsers.computeIfPresent(key.userId(), (ignored, user) -> withoutLeverage(user, key));
            });
            deleted.liquidationIds().forEach(id -> { liquidations.add(id); changedLiquidations.remove(id); });
            algos.addAll(deleted.algoOrderIds());
            deleted.triggerOrderIds().forEach(id -> { triggers.add(id); changedTriggers.remove(id); });
            deleted.treasuryAssets().forEach(asset -> { treasury.add(asset); changedTreasury.remove(asset); });
        }

        private CoreExportEvent.Tombstones seal() {
            return new CoreExportEvent.Tombstones(List.copyOf(users), List.copyOf(balances.values()),
                    List.copyOf(reservations.values()), List.copyOf(orders), List.copyOf(positions.values()),
                    List.copyOf(leverages.values()), List.copyOf(liquidations), List.copyOf(algos),
                    List.copyOf(triggers), List.copyOf(treasury));
        }
    }

    private static com.surprising.aeron.protocol.CoreUserStateView withoutBalance(
            com.surprising.aeron.protocol.CoreUserStateView user, String asset) {
        return new com.surprising.aeron.protocol.CoreUserStateView(user.productLine(), user.userId(),
                user.revision(), user.positionMode(), user.balances().stream()
                .filter(value -> !value.asset().equals(asset)).toList(), user.reservations(),
                user.positions(), user.leverages());
    }

    private static com.surprising.aeron.protocol.CoreUserStateView withoutReservation(
            com.surprising.aeron.protocol.CoreUserStateView user, long orderId) {
        return new com.surprising.aeron.protocol.CoreUserStateView(user.productLine(), user.userId(),
                user.revision(), user.positionMode(), user.balances(), user.reservations().stream()
                .filter(value -> value.orderId() != orderId).toList(), user.positions(), user.leverages());
    }

    private static com.surprising.aeron.protocol.CoreUserStateView withoutPosition(
            com.surprising.aeron.protocol.CoreUserStateView user, CoreExportEvent.UserPositionKey key) {
        return new com.surprising.aeron.protocol.CoreUserStateView(user.productLine(), user.userId(),
                user.revision(), user.positionMode(), user.balances(), user.reservations(), user.positions().stream()
                .filter(value -> !value.symbol().equals(key.symbol())
                        || value.positionSide() != key.positionSide()).toList(), user.leverages());
    }

    private static com.surprising.aeron.protocol.CoreUserStateView withoutLeverage(
            com.surprising.aeron.protocol.CoreUserStateView user, CoreExportEvent.UserLeverageKey key) {
        return new com.surprising.aeron.protocol.CoreUserStateView(user.productLine(), user.userId(),
                user.revision(), user.positionMode(), user.balances(), user.reservations(), user.positions(),
                user.leverages().stream().filter(value -> !value.symbol().equals(key.symbol())
                        || value.marginMode() != key.marginMode()).toList());
    }

    private record PositionKey(String symbol, com.surprising.aeron.protocol.CorePositionSide side)
            implements Comparable<PositionKey> {
        @Override public int compareTo(PositionKey other) {
            int result = symbol.compareTo(other.symbol);
            return result != 0 ? result : side.compareTo(other.side);
        }
    }

    private record LeverageKey(String symbol, com.surprising.aeron.protocol.CoreMarginMode mode)
            implements Comparable<LeverageKey> {
        @Override public int compareTo(LeverageKey other) {
            int result = symbol.compareTo(other.symbol);
            return result != 0 ? result : mode.compareTo(other.mode);
        }
    }

    private record AssetKey(long userId, String asset) implements Comparable<AssetKey> {
        @Override public int compareTo(AssetKey other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : asset.compareTo(other.asset);
        }
    }

    private record ReservationKey(long userId, long orderId) implements Comparable<ReservationKey> {
        @Override public int compareTo(ReservationKey other) {
            int result = Long.compare(userId, other.userId);
            return result != 0 ? result : Long.compare(orderId, other.orderId);
        }
    }

    private record UserPositionKey(long userId, String symbol,
                                   com.surprising.aeron.protocol.CorePositionSide side)
            implements Comparable<UserPositionKey> {
        @Override public int compareTo(UserPositionKey other) {
            int result = Long.compare(userId, other.userId);
            if (result == 0) result = symbol.compareTo(other.symbol);
            return result != 0 ? result : side.compareTo(other.side);
        }
    }

    private record UserLeverageKey(long userId, String symbol,
                                   com.surprising.aeron.protocol.CoreMarginMode mode)
            implements Comparable<UserLeverageKey> {
        @Override public int compareTo(UserLeverageKey other) {
            int result = Long.compare(userId, other.userId);
            if (result == 0) result = symbol.compareTo(other.symbol);
            return result != 0 ? result : mode.compareTo(other.mode);
        }
    }

    void assertHealthy() {
        Throwable failure = materializationFailure.get();
        if (failure != null) throw new CompletionException("Core Fact materialization failed", failure);
        if (closed) throw new IllegalStateException("Core Fact materializer is closed");
    }

    private long materializationBacklog() {
        return Math.subtractExact(submittedMaterializations, completedMaterializations);
    }

    private void requireActivated() {
        if (!activated) throw new IllegalStateException("Core Fact materializer is not activated");
    }

    private void runMaterializer() {
        ArrayList<PendingExport> batch = new ArrayList<>(materializationBatchSize);
        while (!closed || materializationBacklog() > 0) {
            try {
                PendingExport first = materializationQueue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                materializationQueue.drainTo(batch, materializationBatchSize - 1);
                long bytes = 0;
                for (PendingExport task : batch) {
                    Throwable priorFailure = materializationFailure.get();
                    if (priorFailure != null) {
                        completedMaterializations = Math.incrementExact(completedMaterializations);
                        task.completeExceptionally(priorFailure, this);
                        continue;
                    }
                    boolean released = false;
                    try {
                        if (task.header.sourceSequence() != nextMaterializationSequence) {
                            throw new IllegalStateException("Core Fact materialization sequence gap");
                        }
                        CoreExportEvent event = task.draft.materialize(nextMaterializationSequence);
                        byte[] encoded = encoder.encode(event);
                        int actualLength = Math.addExact(CoreProtocol.HEADER_LENGTH, encoded.length);
                        if (actualLength > task.encodedLength) {
                            throw new IllegalStateException("Core Fact exceeded deterministic reservation: actual="
                                    + actualLength + ", reserved=" + task.encodedLength
                                    + ", sequence=" + task.header.sourceSequence()
                                    + ", estimatedItems=" + task.draft.itemCount()
                                    + ", users=" + event.changedUsers().size()
                                    + ", orders=" + event.changedOrders().size()
                                    + ", executions=" + event.executions().size()
                                    + ", tombstones=" + event.tombstones().itemCount());
                        }
                        CoreMessage message = CoreMessage.owned(task.header, encoded);
                        nextMaterializationSequence = Math.incrementExact(nextMaterializationSequence);
                        bytes = Math.addExact(bytes, actualLength);
                        completedMaterializations = Math.incrementExact(completedMaterializations);
                        released = true;
                        task.complete(new MaterializedExport(message, actualLength), this);
                    } catch (Throwable failure) {
                        poison(failure);
                        if (!released) {
                            completedMaterializations = Math.incrementExact(completedMaterializations);
                            released = true;
                        }
                        task.completeExceptionally(failure, this);
                    } finally {
                        if (!released) completedMaterializations = Math.incrementExact(completedMaterializations);
                    }
                }
                materializationBatchCount++;
                materializationBatchItems += batch.size();
                materializationBatchBytes = Math.addExact(materializationBatchBytes, bytes);
                batch.clear();
                if (materializationFailure.get() != null) {
                    failQueuedMaterializations(materializationFailure.get());
                    return;
                }
            } catch (InterruptedException interrupted) {
                if (!closed) {
                    poison(interrupted);
                    failQueuedMaterializations(interrupted);
                    return;
                }
            } catch (Throwable failure) {
                poison(failure);
                failQueuedMaterializations(failure);
                return;
            }
        }
    }

    private void failQueuedMaterializations(Throwable failure) {
        PendingExport task;
        while ((task = materializationQueue.poll()) != null) {
            completedMaterializations = Math.incrementExact(completedMaterializations);
            task.completeExceptionally(failure, this);
        }
    }

    private void failPendingMaterializations(Throwable failure) {
        for (PendingExport export : pending) {
            if (!export.ready()) export.completeExceptionally(failure, this);
        }
    }

    private void poison(Throwable failure) {
        if (materializationFailure.compareAndSet(null, failure)) errorCount++;
    }

    private void validateReservation(AdmissionReservation reservation) {
        assertHealthy();
        if (reservation == null || reservation.owner != this || reservation.closed
                || reservation.remainingEvents < 1 || reservation.remainingBytes < 1) {
            throw new IllegalStateException("invalid or consumed export admission reservation");
        }
    }

    Metrics metrics() {
        return new Metrics(pending.size(), maxBacklog, closed ? pending.size() : -1,
                materializationBatchCount, materializationBatchItems, materializationBatchBytes,
                materializationBacklog(), acknowledgedMaterializationItems.get(),
                acknowledgedMaterializationBytes.get(), reservedEvents, reservedBytes,
                rejectionCount, errorCount, timeoutCount);
    }

    @FunctionalInterface
    interface EventEncoder {
        byte[] encode(CoreExportEvent event);
    }

    private record MaterializedExport(CoreMessage message, int actualLength) {
    }

    private static final class SpscTaskQueue<E> {
        private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Object[].class);

        private final Object[] slots;
        private final int mask;
        private volatile long producerSequence;
        private volatile long consumerSequence;
        private volatile Thread consumerWaiter;
        private int batchDepth;
        private boolean signalPending;

        private SpscTaskQueue(int requestedCapacity) {
            if (requestedCapacity < 1) throw new IllegalArgumentException("queue capacity must be positive");
            int capacity = 1;
            while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
            slots = new Object[capacity];
            mask = capacity - 1;
        }

        private boolean offer(E value) {
            Objects.requireNonNull(value, "queue value");
            long sequence = producerSequence;
            if (sequence - consumerSequence >= slots.length) return false;
            int index = (int) sequence & mask;
            if (slotAcquire(index) != null) throw new IllegalStateException("SPSC live slot overwrite");
            slotRelease(index, value);
            producerSequence = sequence + 1;
            if (batchDepth == 0) signalConsumer();
            else signalPending = true;
            return true;
        }

        private void beginBatch() {
            batchDepth = Math.incrementExact(batchDepth);
        }

        private void endBatch() {
            if (batchDepth <= 0) throw new IllegalStateException("SPSC producer batch is not active");
            batchDepth--;
            if (batchDepth == 0 && signalPending) {
                signalPending = false;
                signalConsumer();
            }
        }

        private E poll(long timeout, TimeUnit unit) throws InterruptedException {
            E value = poll();
            if (value != null || timeout <= 0) return value;
            long timeoutNanos = unit.toNanos(timeout);
            long deadline = System.nanoTime() + timeoutNanos;
            Thread current = Thread.currentThread();
            consumerWaiter = current;
            try {
                while ((value = poll()) == null) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return null;
                    LockSupport.parkNanos(this, remaining);
                    if (Thread.interrupted()) throw new InterruptedException();
                }
                return value;
            } finally {
                if (consumerWaiter == current) consumerWaiter = null;
            }
        }

        private E poll() {
            long sequence = consumerSequence;
            if (sequence >= producerSequence) return null;
            int index = (int) sequence & mask;
            E value = slotAcquire(index);
            if (value == null) return null;
            slotRelease(index, null);
            consumerSequence = sequence + 1;
            return value;
        }

        @SuppressWarnings("unchecked")
        private E slotAcquire(int index) {
            return (E) SLOT.getAcquire(slots, index);
        }

        private void slotRelease(int index, E value) {
            SLOT.setRelease(slots, index, value);
        }

        private int drainTo(List<E> destination, int maxElements) {
            int count = 0;
            E value;
            while (count < maxElements && (value = poll()) != null) {
                destination.add(value);
                count++;
            }
            return count;
        }

        private boolean isEmpty() {
            return consumerSequence >= producerSequence;
        }

        private void signalConsumer() {
            Thread waiter = consumerWaiter;
            if (waiter != null) LockSupport.unpark(waiter);
        }
    }

    static final class AdmissionReservation {
        private final CoreExportState owner;
        private int remainingEvents;
        private long remainingBytes;
        private boolean closed;

        private AdmissionReservation(CoreExportState owner, int remainingEvents, long remainingBytes) {
            this.owner = owner;
            this.remainingEvents = remainingEvents;
            this.remainingBytes = remainingBytes;
        }

        int remainingEvents() { return remainingEvents; }
    }

    record Metrics(long currentBacklog, long maxBacklog, long endBacklog,
                   long batchCount, long batchItems, long batchBytes,
                   long materializationBacklog, long acknowledgedMaterializationItems,
                   long acknowledgedMaterializationBytes,
                   long reservedEvents, long reservedBytes,
                   long rejectionCount, long errorCount, long timeoutCount) {
    }

    private static int reservedEventLength(Draft draft) {
        return reservedEventLength(draft.command().payloadLength(), draft.itemCount());
    }

    static long maxReservedEventBytes() { return MAX_EVENT_BYTES; }

    static long maxReservedAdmissionBytes(int events) {
        if (events < 1) throw new IllegalArgumentException("event count must be positive");
        return Math.min(MAX_PENDING_BYTES, Math.multiplyExact(MAX_EVENT_BYTES, events));
    }

    private static int reservedEventLength(CoreExportEvent event) {
        int items = event.changedUsers().size() + event.changedOrders().size() + event.executions().size()
                + event.fundingPayments().size() + event.changedLiquidations().size()
                + event.changedTreasuryAssets().size() + event.changedTriggerOrders().size()
                + event.fundsPostings().size() + event.matcherEvidence().size()
                + event.terminalIds().orderIds().size() + event.terminalIds().liquidationIds().size()
                + event.terminalIds().triggerOrderIds().size() + event.tombstones().itemCount();
        return reservedEventLength(event.commandPayloadUnsafe().length, items);
    }

    private static int reservedEventLength(int commandPayloadLength, int itemCount) {
        long reserved = Math.addExact(CoreProtocol.HEADER_LENGTH + 4_096L + commandPayloadLength,
                Math.multiplyExact(2_048L, itemCount));
        if (reserved > MAX_EVENT_BYTES) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export fact exceeds event limit");
        }
        return Math.toIntExact(reserved);
    }

    private static int configuredCapacity() {
        int value = Integer.getInteger("surprising.aeron.export-materialization-capacity", MAX_PENDING_EVENTS);
        if (value < 1 || value > MAX_PENDING_EVENTS) {
            throw new IllegalArgumentException("export materialization capacity is outside supported bounds");
        }
        return value;
    }

    private static long configuredByteCapacity() {
        long value = Long.getLong("surprising.aeron.export-pending-bytes", DEFAULT_PENDING_BYTES);
        if (value < MAX_EVENT_BYTES || value > MAX_PENDING_BYTES) {
            throw new IllegalArgumentException("export byte capacity is outside supported bounds");
        }
        return value;
    }

    private static int configuredBatchSize(int capacity) {
        int value = Integer.getInteger("surprising.aeron.export-materialization-batch-size",
                Math.min(64, capacity));
        if (value < 1 || value > capacity) {
            throw new IllegalArgumentException("export materialization batch size is outside supported bounds");
        }
        return value;
    }
}
