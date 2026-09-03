package com.surprising.aeron.service;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.product.api.ProductLine;
import java.util.List;

/**
 * Empty snapshot compatibility state after removal of the Core-Fact exporter.
 * Trading commits and recovery do not depend on an application outbox.
 */
final class CoreExportState implements AutoCloseable {
    static final int MAX_PENDING_EVENTS = 1_000_000;
    private static final int STATUS_CAPACITY = 1;
    private static final long STATUS_BYTE_CAPACITY = 1;

    private final long acknowledgedSequence;
    private final long nextSequence;
    private boolean activated;
    private boolean closed;

    CoreExportState() {
        this(0);
    }

    CoreExportState(EventEncoder ignored) {
        this(0);
    }

    private CoreExportState(long initialSequence) {
        if (initialSequence < 0) throw new IllegalArgumentException("initial sequence is negative");
        acknowledgedSequence = initialSequence;
        nextSequence = Math.incrementExact(initialSequence);
    }

    static CoreExportState passive() {
        return passive(0);
    }

    static CoreExportState passive(long initialSequence) {
        return new CoreExportState(initialSequence);
    }

    static CoreExportState restore(ProductLine expectedProductLine, long acknowledgedSequence,
                                   long nextSequence, List<CoreMessage> pending) {
        return restore(expectedProductLine, acknowledgedSequence, nextSequence, pending, List.of());
    }

    static CoreExportState restore(ProductLine expectedProductLine, long acknowledgedSequence,
                                   long nextSequence, List<CoreMessage> pending,
                                   List<Integer> reservedLengths) {
        if (expectedProductLine == null || pending == null || reservedLengths == null
                || !pending.isEmpty() || !reservedLengths.isEmpty()
                || nextSequence != Math.incrementExact(acknowledgedSequence)) {
            throw new IllegalArgumentException("Core-Fact exporter state is no longer supported");
        }
        return new CoreExportState(acknowledgedSequence);
    }

    void activate() {
        assertHealthy();
        activated = true;
    }

    boolean activated() { return activated; }
    boolean enabled() { return false; }
    long acknowledgedSequence() { return acknowledgedSequence; }
    long nextSequence() { return nextSequence; }
    int pendingCount() { return 0; }
    long pendingDigest() { return 0; }
    int encodedPendingCount() { return 0; }
    long materializedThroughSequence() { return acknowledgedSequence; }
    List<CoreMessage> pending() { return List.of(); }
    Iterable<CoreMessage> pendingEvents() { return List.of(); }
    List<Integer> pendingReservedLengths() { return List.of(); }

    void beginMaterializationBatch() { assertHealthy(); }
    void endMaterializationBatch() { assertHealthy(); }

    List<Long> acknowledge(AckExportCommand ignored) {
        assertHealthy();
        return List.of();
    }

    List<CoreMessage> batch(int maxEvents) {
        assertHealthy();
        if (maxEvents < 1) throw new IllegalArgumentException("max events must be positive");
        return List.of();
    }

    CoreExportStatus status() {
        assertHealthy();
        return new CoreExportStatus(acknowledgedSequence, nextSequence, 0, 0,
                STATUS_CAPACITY, STATUS_BYTE_CAPACITY);
    }

    Snapshot snapshot() {
        assertHealthy();
        return new Snapshot(acknowledgedSequence, nextSequence, List.of(), List.of(), 0);
    }

    Metrics metrics() {
        return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    void assertHealthy() {
        if (closed) throw new IllegalStateException("export compatibility state is closed");
    }

    @Override
    public void close() {
        closed = true;
    }

    static long maxReservedEventBytes() { return 1; }

    static long maxReservedAdmissionBytes(int events) {
        if (events < 1) throw new IllegalArgumentException("event count must be positive");
        return events;
    }

    @FunctionalInterface
    interface EventEncoder {
        CoreMessage encode(CoreExportEvent event);
    }

    record Snapshot(long acknowledgedSequence, long nextSequence, List<CoreMessage> pendingEvents,
                    List<Integer> pendingReservedLengths, long pendingDigest) {
        Snapshot {
            pendingEvents = List.copyOf(pendingEvents);
            pendingReservedLengths = List.copyOf(pendingReservedLengths);
        }

        int pendingCount() { return pendingEvents.size(); }
    }

    record Metrics(long currentBacklog, long maxBacklog, long endBacklog,
                   long batchCount, long batchItems, long batchBytes,
                   long materializationBacklog, long acknowledgedMaterializationItems,
                   long acknowledgedMaterializationBytes, long reservedEvents, long reservedBytes,
                   long rejectionCount, long errorCount, long timeoutCount) {
    }
}
