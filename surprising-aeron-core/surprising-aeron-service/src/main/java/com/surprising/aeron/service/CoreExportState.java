package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
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
            PendingExport restored = pendingExport(event, terminalOrderIds(decoded));
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
                beforeBusinessStateHash, beforeFundsStateHash, fundsStateHash, matcherTransition,
                clusterPosition, fundsDelta.views());
        CoreMessage message;
        try {
            message = CoreMessage.owned(command.header().exportEvent(sequence), CoreExportCodec.encodeEvent(event));
        } catch (IllegalArgumentException exception) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export fact exceeds event limit");
        }
        int eventBytes = encodedLength(message);
        if (pendingBytes + eventBytes > MAX_PENDING_BYTES) {
            throw new CoreStateRejectedException("EXPORT_BACKLOG_FULL", "export backlog reached byte limit");
        }
        PendingExport appended = new PendingExport(message, terminalOrderIds(event), eventBytes,
                eventDigest(message));
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

    private static int encodedLength(CoreMessage message) {
        return Math.addExact(CoreProtocol.HEADER_LENGTH, message.payloadLength());
    }

    private static long eventDigest(CoreMessage message) {
        long hash = 0xcbf29ce484222325L;
        var header = message.header();
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
        for (byte value : message.payloadUnsafe()) {
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

    private static PendingExport pendingExport(CoreMessage message, List<Long> terminalOrderIds) {
        return new PendingExport(message, terminalOrderIds, encodedLength(message), eventDigest(message));
    }

    private record PendingExport(CoreMessage message, List<Long> terminalOrderIds,
                                 int encodedLength, long digest) {
        private PendingExport {
            terminalOrderIds = List.copyOf(terminalOrderIds);
            if (encodedLength < CoreProtocol.HEADER_LENGTH) {
                throw new IllegalArgumentException("invalid pending export length");
            }
        }
    }
}
