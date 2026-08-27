package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
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
import com.surprising.aeron.service.state.FundsDelta;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongFunction;

final class CoreExportState implements AutoCloseable {

    static final int MAX_PENDING_EVENTS = 1_000_000;
    static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EVENT_BYTES = CoreProtocol.HEADER_LENGTH
            + (long) CoreMessageCodec.MAX_PAYLOAD_LENGTH;
    private long acknowledgedSequence;
    private long nextSequence;
    private final ArrayDeque<PendingExport> pending;
    private long pendingBytes;
    private long pendingDigest;
    private final ExecutorService materializer;

    CoreExportState() {
        this(0, 1, List.of());
    }

    private CoreExportState(long acknowledgedSequence, long nextSequence, List<CoreMessage> pending) {
        if (acknowledgedSequence < 0 || nextSequence <= acknowledgedSequence || pending == null
                || pending.size() > MAX_PENDING_EVENTS || nextSequence - acknowledgedSequence - 1 != pending.size()) {
            throw new IllegalArgumentException("invalid export state");
        }
        this.acknowledgedSequence = acknowledgedSequence;
        this.nextSequence = nextSequence;
        this.pending = new ArrayDeque<>(pending.size());
        this.materializer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "core-fact-materializer");
            thread.setDaemon(true);
            return thread;
        });
        long expectedSequence = Math.incrementExact(acknowledgedSequence);
        for (CoreMessage event : pending) {
            CoreExportEvent decoded = CoreExportCodec.decodeEvent(event.payloadUnsafe());
            if (event.header().kind() != WireMessageKind.EXPORT_EVENT
                    || event.header().sourceSequence() != expectedSequence
                    || decoded.exportSequence() != expectedSequence) {
                throw new IllegalArgumentException("non-contiguous export state");
            }
            PendingExport restored = pendingExport(event, decoded, terminalOrderIds(decoded));
            this.pending.add(restored);
            pendingBytes = Math.addExact(pendingBytes, restored.encodedLength());
            pendingDigest ^= restored.digest();
            expectedSequence = Math.incrementExact(expectedSequence);
        }
        if (pendingBytes > MAX_PENDING_BYTES) {
            throw new IllegalArgumentException("export state exceeds byte limit");
        }
    }

    static CoreExportState restore(long acknowledgedSequence, long nextSequence, List<CoreMessage> pending) {
        return new CoreExportState(acknowledgedSequence, nextSequence, pending);
    }

    long append(Draft draft) {
        if (pending.size() >= MAX_PENDING_EVENTS) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached hard limit");
        }
        if (draft == null) throw new IllegalArgumentException("core fact draft is required");
        long sequence = nextSequence;
        int eventBytes = reservedEventLength(draft);
        if (pendingBytes + eventBytes > MAX_PENDING_BYTES) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached byte limit");
        }
        CoreMessageHeader header = draft.command().header().exportEvent(sequence);
        CompletableFuture<MaterializedExport> completion = CompletableFuture.supplyAsync(() -> {
            CoreExportEvent event = draft.factory().apply(sequence);
            int actualLength = Math.addExact(CoreProtocol.HEADER_LENGTH, CoreExportCodec.encodedEventLength(event));
            if (actualLength > eventBytes) {
                throw new IllegalStateException("Core Fact exceeded deterministic reservation");
            }
            CoreMessage message = CoreMessage.owned(header, CoreExportCodec.encodeEvent(event));
            return new MaterializedExport(event, message, actualLength);
        }, materializer);
        PendingExport appended = new PendingExport(header, completion, eventBytes,
                draftDigest(header, draft), draft.terminalOrderIds());
        pending.add(appended);
        pendingBytes = Math.addExact(pendingBytes, eventBytes);
        pendingDigest ^= appended.digest();
        nextSequence = Math.incrementExact(nextSequence);
        return sequence;
    }

    boolean hasCapacity() {
        return pending.size() < MAX_PENDING_EVENTS;
    }

    boolean hasCapacityFor() {
        return hasCapacityFor(1);
    }

    boolean hasCapacityFor(int additionalEvents) {
        if (additionalEvents < 1 || pending.size() > MAX_PENDING_EVENTS - additionalEvents) {
            return false;
        }
        return pendingBytes <= MAX_PENDING_BYTES - Math.multiplyExact(MAX_EVENT_BYTES, additionalEvents);
    }

    List<Long> acknowledge(AckExportCommand command) {
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
            terminalOrderIds.addAll(removed.terminalOrderIds());
            pendingBytes = Math.subtractExact(pendingBytes, removed.encodedLength());
            pendingDigest ^= removed.digest();
        }
        acknowledgedSequence = command.throughSequence();
        return List.copyOf(terminalOrderIds);
    }

    List<CoreMessage> batch(int maxEvents) {
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
        return new CoreExportStatus(acknowledgedSequence, nextSequence, pending.size(), pendingBytes,
                MAX_PENDING_EVENTS, MAX_PENDING_BYTES);
    }

    long acknowledgedSequence() {
        return acknowledgedSequence;
    }

    long nextSequence() {
        return nextSequence;
    }

    List<CoreMessage> pending() {
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
        materializer.shutdownNow();
    }

    Snapshot snapshot() {
        return new Snapshot(acknowledgedSequence, nextSequence, pending(), pendingDigest);
    }

    record Snapshot(long acknowledgedSequence, long nextSequence, List<CoreMessage> pendingEvents,
                    long pendingDigest) {
        Snapshot {
            pendingEvents = List.copyOf(pendingEvents);
            if (acknowledgedSequence < 0 || nextSequence <= acknowledgedSequence
                    || nextSequence - acknowledgedSequence - 1 != pendingEvents.size()) {
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

    private static List<Long> terminalOrderIds(CoreExportEvent event) {
        LinkedHashSet<Long> terminal = new LinkedHashSet<>();
        for (var order : event.changedOrders()) {
            if (!"OPEN".equals(order.status())) terminal.add(order.orderId());
        }
        return List.copyOf(terminal);
    }

    private static PendingExport pendingExport(CoreMessage message, CoreExportEvent event,
                                               List<Long> terminalOrderIds) {
        MaterializedExport materialized = new MaterializedExport(event, message, encodedLength(message));
        return new PendingExport(message.header(), CompletableFuture.completedFuture(materialized),
                reservedEventLength(event), eventDigest(message.header(), event), terminalOrderIds);
    }

    private static final class PendingExport {
        private final CoreMessageHeader header;
        private final CompletableFuture<MaterializedExport> completion;
        private final int encodedLength;
        private final long digest;
        private final List<Long> terminalOrderIds;

        private PendingExport(CoreMessageHeader header, CompletableFuture<MaterializedExport> completion,
                              int encodedLength, long digest, List<Long> terminalOrderIds) {
            if (header == null || completion == null || terminalOrderIds == null) {
                throw new IllegalArgumentException("invalid pending export");
            }
            this.header = header;
            this.completion = completion;
            this.encodedLength = encodedLength;
            this.digest = digest;
            this.terminalOrderIds = List.copyOf(terminalOrderIds);
            if (encodedLength < CoreProtocol.HEADER_LENGTH) {
                throw new IllegalArgumentException("invalid pending export length");
            }
        }

        private CoreMessage message() {
            return completion.join().message();
        }

        private List<Long> terminalOrderIds() { return terminalOrderIds; }
        private int encodedLength() { return encodedLength; }
        private long digest() { return digest; }
        private boolean encoded() { return completion.isDone() && !completion.isCompletedExceptionally(); }
        private boolean ready() { return completion.isDone(); }
    }

    record Draft(CoreMessage command, ResponseStatus status,
                 com.surprising.aeron.protocol.CoreResultCode resultCode,
                 long appliedCommandCount, long businessStateHash,
                 long beforeBusinessStateHash, long beforeFundsStateHash, long fundsStateHash,
                 long topologyHash, long laneRevisionHash, CoreMatcherTransition matcherTransition,
                 long clusterPosition, int itemCount, List<Long> terminalOrderIds,
                 LongFunction<CoreExportEvent> factory) {
        Draft {
            if (command == null || status == null || resultCode == null || appliedCommandCount < 0
                    || matcherTransition == null || clusterPosition < 0 || itemCount < 0
                    || terminalOrderIds == null || factory == null) {
                throw new IllegalArgumentException("invalid Core Fact draft");
            }
            terminalOrderIds = List.copyOf(terminalOrderIds);
        }
    }

    private record MaterializedExport(CoreExportEvent event, CoreMessage message, int actualLength) {
    }

    private static int reservedEventLength(Draft draft) {
        return reservedEventLength(draft.command().payloadLength(), draft.itemCount());
    }

    private static int reservedEventLength(CoreExportEvent event) {
        int items = event.changedUsers().size() + event.changedOrders().size() + event.executions().size()
                + event.fundingPayments().size() + event.changedLiquidations().size()
                + event.changedTreasuryAssets().size() + event.changedTriggerOrders().size()
                + event.fundsPostings().size();
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
}
