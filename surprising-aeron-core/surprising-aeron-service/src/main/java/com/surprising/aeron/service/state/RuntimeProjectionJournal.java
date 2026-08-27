package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

public final class RuntimeProjectionJournal implements AutoCloseable {

    private static final int MIN_CAPACITY = 1_024;
    private static final int MAX_CAPACITY = 1 << 20;
    private static final long IDLE_PARK_NANOS = 50_000L;
    private static final int MAX_PROJECTION_BATCH = 1_024;
    private static final int MAX_OWNER_REBASE_ENTRIES = 2_048;

    private final AtomicReferenceArray<Record> entries;
    private final int mask;
    private final int projectionBatchSize;
    private final boolean busySpin;
    private final Thread projector;
    private volatile ProjectionVersion projected;
    private volatile long publishedSequence;
    private volatile long consumedSequence;
    private volatile long requestedSequence;
    private volatile Throwable failure;
    private volatile boolean closed;

    public RuntimeProjectionJournal(ProductLine productLine, TradingCoreState initial,
                                    long businessStateHash, long fundsStateHash) {
        if (productLine == null || initial == null || initial.productLine() != productLine) {
            throw new IllegalArgumentException("invalid projection journal state");
        }
        int requested = Integer.getInteger("surprising.aeron.projection-journal-capacity", 65_536);
        int capacity = normalizedCapacity(requested);
        entries = new AtomicReferenceArray<>(capacity);
        mask = capacity - 1;
        projectionBatchSize = configuredBatchSize(capacity);
        busySpin = Boolean.getBoolean("surprising.aeron.projection-busy-spin");
        projected = new ProjectionVersion(0, initial, businessStateHash, fundsStateHash);
        projector = Thread.ofPlatform()
                .daemon(true)
                .name("core-projection-" + productLine.name().toLowerCase(java.util.Locale.ROOT))
                .unstarted(this::runProjector);
        projector.start();
    }

    public long publish(RuntimeCommitEntry entry, long businessStateHash, long fundsStateHash) {
        requireHealthy();
        long next = Math.incrementExact(publishedSequence);
        if (entry == null || entry.sequence() != next) {
            throw new IllegalStateException("projection journal sequence gap");
        }
        if (next - consumedSequence > entries.length()) {
            throw new IllegalStateException("projection journal exhausted after Cluster Log commit");
        }
        int index = (int) (next & mask);
        if (entries.get(index) != null) {
            throw new IllegalStateException("projection journal slot was not reclaimed");
        }
        entries.set(index, new Record(entry, businessStateHash, fundsStateHash));
        publishedSequence = next;
        if (next - consumedSequence == 1) LockSupport.unpark(projector);
        return next;
    }

    public long publishedSequence() {
        return publishedSequence;
    }

    public long projectedSequence() {
        return projected.sequence();
    }

    public long lag() {
        return Math.subtractExact(publishedSequence, consumedSequence);
    }

    public ProjectionVersion current() {
        requireHealthy();
        return projected;
    }

    public TransitionVersion transitionViewToPublished(long afterProjectedSequence) {
        requireHealthy();
        for (int attempt = 0; attempt < 2; attempt++) {
            ProjectionVersion base = projected;
            long targetSequence = publishedSequence;
            if (base.sequence() <= afterProjectedSequence) return null;
            if (targetSequence - base.sequence() > MAX_OWNER_REBASE_ENTRIES) return null;
            TradingCoreState state = base.state();
            long businessStateHash = base.businessStateHash();
            long fundsStateHash = base.fundsStateHash();
            boolean retry = false;
            for (long sequence = base.sequence() + 1; sequence <= targetSequence; sequence++) {
                Record record = entries.get((int) (sequence & mask));
                if (record == null) {
                    retry = true;
                    break;
                }
                state = record.entry().transitionView(state);
                businessStateHash = record.businessStateHash();
                fundsStateHash = record.fundsStateHash();
            }
            if (!retry) {
                return new TransitionVersion(base.sequence(), targetSequence, state,
                        businessStateHash, fundsStateHash);
            }
        }
        return null;
    }

    public ProjectionVersion await(long sequence, long deadlineNanos, boolean verifyHashes) {
        if (sequence < 0 || sequence != publishedSequence || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid projection fence");
        }
        requestProjection(sequence);
        while (projected.sequence() < sequence) {
            requireHealthy();
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("projection fence timed out");
            }
            if (busySpin) Thread.onSpinWait(); else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
        }
        ProjectionVersion result = projected;
        if (verifyHashes) {
            long business = RollingBusinessStateHash.compute(result.state());
            long funds = RollingFundsStateHash.compute(result.state());
            if (business != result.businessStateHash() || funds != result.fundsStateHash()) {
                IllegalStateException mismatch = new IllegalStateException("typed projection hash mismatch");
                failure = mismatch;
                throw mismatch;
            }
        }
        return result;
    }

    public void requestProjection(long sequence) {
        requireHealthy();
        if (sequence < consumedSequence || sequence > publishedSequence) {
            throw new IllegalArgumentException("invalid requested projection sequence");
        }
        if (sequence > requestedSequence) requestedSequence = sequence;
        LockSupport.unpark(projector);
    }

    private void runProjector() {
        try {
            while (!closed || consumedSequence < publishedSequence) {
                long firstSequence = Math.incrementExact(consumedSequence);
                long availableSequence = publishedSequence;
                if (firstSequence > availableSequence) {
                    if (busySpin) Thread.onSpinWait(); else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                    continue;
                }
                long availableCount = availableSequence - consumedSequence;
                if (!closed && availableCount < projectionBatchSize && requestedSequence <= consumedSequence) {
                    if (busySpin) Thread.onSpinWait(); else LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                    continue;
                }
                long lastSequence = Math.min(availableSequence,
                        Math.addExact(firstSequence, MAX_PROJECTION_BATCH - 1L));
                if (lastSequence == firstSequence) {
                    int index = (int) (firstSequence & mask);
                    Record record;
                    while ((record = entries.get(index)) == null) Thread.onSpinWait();
                    TradingCoreState nextState = record.entry().project(projected.state());
                    projected = new ProjectionVersion(firstSequence, nextState,
                            record.businessStateHash(), record.fundsStateHash());
                    entries.set(index, null);
                    consumedSequence = firstSequence;
                    continue;
                }
                List<RuntimeCommitEntry> batch = new ArrayList<>((int) (lastSequence - firstSequence + 1));
                Record lastRecord = null;
                for (long sequence = firstSequence; sequence <= lastSequence; sequence++) {
                    int index = (int) (sequence & mask);
                    Record record;
                    while ((record = entries.get(index)) == null) {
                        if (closed && sequence > publishedSequence) return;
                        Thread.onSpinWait();
                    }
                    batch.add(record.entry());
                    lastRecord = record;
                }
                TradingCoreState nextState = projected.state();
                for (RuntimeCommitEntry entry : batch) nextState = entry.project(nextState);
                projected = new ProjectionVersion(lastSequence, nextState,
                        lastRecord.businessStateHash(), lastRecord.fundsStateHash());
                for (long sequence = firstSequence; sequence <= lastSequence; sequence++) {
                    entries.set((int) (sequence & mask), null);
                }
                consumedSequence = lastSequence;
            }
        } catch (Throwable projectorFailure) {
            failure = projectorFailure;
        }
    }

    private void requireHealthy() {
        Throwable currentFailure = failure;
        if (currentFailure != null) {
            throw new IllegalStateException("runtime projection journal failed", currentFailure);
        }
        if (closed) throw new IllegalStateException("runtime projection journal is closed");
    }

    @Override
    public void close() {
        closed = true;
        LockSupport.unpark(projector);
        try {
            projector.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (projector.isAlive()) projector.interrupt();
    }

    private static int normalizedCapacity(int requested) {
        if (requested < MIN_CAPACITY || requested > MAX_CAPACITY) {
            throw new IllegalArgumentException("projection journal capacity is outside supported bounds");
        }
        int value = 1;
        while (value < requested) value <<= 1;
        return value;
    }

    private static int configuredBatchSize(int capacity) {
        int batchSize = Integer.getInteger("surprising.aeron.projection-batch-size", 1);
        if (batchSize <= 0 || batchSize > MAX_PROJECTION_BATCH || batchSize > capacity) {
            throw new IllegalArgumentException("projection batch size is outside supported bounds");
        }
        return batchSize;
    }

    private record Record(RuntimeCommitEntry entry, long businessStateHash, long fundsStateHash) {
    }

    public record ProjectionVersion(long sequence, TradingCoreState state,
                                    long businessStateHash, long fundsStateHash) {
        public ProjectionVersion {
            if (sequence < 0 || state == null) throw new IllegalArgumentException("invalid projection version");
        }
    }

    public record TransitionVersion(long projectedSequence, long publishedSequence,
                                    TradingCoreState state, long businessStateHash, long fundsStateHash) {
        public TransitionVersion {
            if (projectedSequence < 0 || publishedSequence < projectedSequence || state == null) {
                throw new IllegalArgumentException("invalid projection transition version");
            }
        }
    }
}
