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

final class CoreExportState {

    static final int MAX_PENDING_EVENTS = 1_000_000;
    static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EVENT_BYTES = CoreProtocol.HEADER_LENGTH
            + (long) CoreMessageCodec.MAX_PAYLOAD_LENGTH;
    private long acknowledgedSequence;
    private long nextSequence;
    private final ArrayDeque<PendingExport> pending;
    private long pendingBytes;
    private long pendingDigest;

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

    long append(CoreMessage command, ResponseStatus status, com.surprising.aeron.protocol.CoreResultCode resultCode,
                long appliedCommandCount, long businessStateHash,
                long beforeBusinessStateHash, long beforeFundsStateHash, long fundsStateHash,
                long topologyHash, long laneRevisionHash,
                CoreMatcherTransition matcherTransition, long clusterPosition,
                FundsDelta fundsDelta,
                List<com.surprising.aeron.protocol.CoreUserStateView> changedUsers,
                List<com.surprising.aeron.protocol.CoreOrderStateView> changedOrders,
                List<com.surprising.aeron.protocol.CoreExecutionView> executions,
                List<com.surprising.aeron.protocol.CoreFundingPaymentView> fundingPayments,
                List<com.surprising.aeron.protocol.CoreLiquidationView> changedLiquidations,
                List<com.surprising.aeron.protocol.CoreTreasuryAssetView> changedTreasuryAssets,
                List<com.surprising.aeron.protocol.CoreTriggerOrderStateView> changedTriggerOrders) {
        if (pending.size() >= MAX_PENDING_EVENTS) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached hard limit");
        }
        long sequence = nextSequence;
        if (fundsDelta == null || matcherTransition == null) {
            throw new IllegalArgumentException("core fact funds and matcher transition are required");
        }
        CoreExportEvent event = new CoreExportEvent(sequence, appliedCommandCount, businessStateHash,
                command.header().commandId(), command.header().messageType(), status, resultCode,
                command.header().userId(), command.payloadUnsafe(), changedUsers, changedOrders, executions,
                fundingPayments, changedLiquidations, changedTreasuryAssets, changedTriggerOrders,
                beforeBusinessStateHash, beforeFundsStateHash, fundsStateHash,
                matcherTransition.routeVersion(), topologyHash, laneRevisionHash, appliedCommandCount,
                matcherTransition, clusterPosition, fundsDelta.views());
        int eventBytes;
        try {
            eventBytes = Math.addExact(CoreProtocol.HEADER_LENGTH, CoreExportCodec.encodedEventLength(event));
        } catch (IllegalArgumentException exception) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export fact exceeds event limit");
        }
        if (pendingBytes + eventBytes > MAX_PENDING_BYTES) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached byte limit");
        }
        CoreMessageHeader header = command.header().exportEvent(sequence);
        PendingExport appended = new PendingExport(header, event, null, terminalOrderIds(event), eventBytes,
                eventDigest(header, event));
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
            CoreMessage event = pendingEvent.message();
            int eventLength = pendingEvent.encodedLength();
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
        hash = digestLong(hash, event.appliedCommandCount());
        hash = digestLong(hash, event.businessStateHash());
        hash = digestLong(hash, event.commandType().wireCode());
        hash = digestLong(hash, event.commandStatus().wireCode());
        hash = digestLong(hash, event.resultCode().wireCode());
        hash = digestLong(hash, event.beforeBusinessStateHash());
        hash = digestLong(hash, event.beforeFundsStateHash());
        hash = digestLong(hash, event.fundsStateHash());
        hash = digestLong(hash, event.topologyHash());
        hash = digestLong(hash, event.laneRevisionHash());
        hash = digestLong(hash, event.committedCoreSequence());
        hash = digestLong(hash, event.matcherTransition().matcherShardId());
        hash = digestLong(hash, event.matcherTransition().sequenceBefore());
        hash = digestLong(hash, event.matcherTransition().sequenceAfter());
        hash = digestLong(hash, event.matcherTransition().prefixBefore());
        hash = digestLong(hash, event.matcherTransition().prefixAfter());
        hash = digestLong(hash, event.clusterPosition());
        hash = digestLong(hash, event.changedUsers().size());
        hash = digestLong(hash, event.changedOrders().size());
        hash = digestLong(hash, event.executions().size());
        hash = digestLong(hash, event.fundingPayments().size());
        hash = digestLong(hash, event.changedLiquidations().size());
        hash = digestLong(hash, event.changedTreasuryAssets().size());
        hash = digestLong(hash, event.changedTriggerOrders().size());
        hash = digestLong(hash, event.fundsPostings().size());
        hash = digestValues(hash, event.changedUsers());
        hash = digestValues(hash, event.changedOrders());
        hash = digestValues(hash, event.executions());
        hash = digestValues(hash, event.fundingPayments());
        hash = digestValues(hash, event.changedLiquidations());
        hash = digestValues(hash, event.changedTreasuryAssets());
        hash = digestValues(hash, event.changedTriggerOrders());
        hash = digestValues(hash, event.fundsPostings());
        for (byte value : event.commandPayloadUnsafe()) {
            hash ^= Byte.toUnsignedInt(value);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long digestValues(long hash, List<?> values) {
        long result = hash;
        for (Object value : values) result = digestLong(result, value.hashCode());
        return result;
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
        return new PendingExport(message.header(), event, message, terminalOrderIds,
                encodedLength(message), eventDigest(message.header(), event));
    }

    private static final class PendingExport {
        private final CoreMessageHeader header;
        private final CoreExportEvent event;
        private CoreMessage encoded;
        private final List<Long> terminalOrderIds;
        private final int encodedLength;
        private final long digest;

        private PendingExport(CoreMessageHeader header, CoreExportEvent event, CoreMessage encoded,
                              List<Long> terminalOrderIds, int encodedLength, long digest) {
            if (header == null || event == null || terminalOrderIds == null) {
                throw new IllegalArgumentException("invalid pending export");
            }
            this.header = header;
            this.event = event;
            this.encoded = encoded;
            this.terminalOrderIds = List.copyOf(terminalOrderIds);
            this.encodedLength = encodedLength;
            this.digest = digest;
            if (encodedLength < CoreProtocol.HEADER_LENGTH) {
                throw new IllegalArgumentException("invalid pending export length");
            }
        }

        private CoreMessage message() {
            if (encoded == null) {
                CoreMessage materialized = CoreMessage.owned(header, CoreExportCodec.encodeEvent(event));
                if (CoreExportState.encodedLength(materialized) != encodedLength) {
                    throw new IllegalStateException("lazy Core Fact length differs from admission length");
                }
                encoded = materialized;
            }
            return encoded;
        }

        private List<Long> terminalOrderIds() { return terminalOrderIds; }
        private int encodedLength() { return encodedLength; }
        private long digest() { return digest; }
        private boolean encoded() { return encoded != null; }
    }
}
