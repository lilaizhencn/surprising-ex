package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

public final class RuntimeCommitJournal implements AutoCloseable {

    private static final int MIN_CAPACITY = 1_024;
    private static final int MAX_CAPACITY = 1 << 20;
    private static final int MAX_PROJECTION_BATCH = 1_024;
    private static final long DEFAULT_AWAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long IDLE_PARK_NANOS = 50_000L;
    private static final long MAX_RESERVED_PATCH_BYTES = 16L << 20;

    private final AtomicReferenceArray<RuntimeCommitPatch> entries;
    private final long[] entryBytes;
    private final int mask;
    private final int projectionBatchSize;
    private final long projectionBatchBytes;
    private final long batchDelayNanos;
    private final long capacityBytes;
    private final WaitStrategy waitStrategy;
    private final Thread projector;
    private final RuntimeProjectionState replica;
    private final RuntimeProjectionPoint initialPoint;
    private volatile ProjectionVersion projected;
    private volatile long publishedSequence;
    private volatile long consumedSequence;
    private volatile long requestedSequence;
    private volatile Throwable failure;
    private volatile boolean closed;
    private volatile ProjectorGate projectorGate;
    private volatile CountDownLatch projectionWaiterEntered;
    private long reservedEntries;
    private long reservedBytes;
    private volatile long publishedBytes;
    private volatile long consumedBytes;
    private long maxBacklogBytes;
    private long maxBacklog;
    private volatile long batchCount;
    private volatile long batchItems;
    private volatile long batchBytes;
    private volatile long waitCount;
    private long rejectionCount;
    private volatile long errorCount;
    private long timeoutCount;
    private RuntimeCommitPatch lastAppliedPatch;
    private boolean activated;

    public RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial,
                                long businessStateHash, long fundsStateHash) {
        this(productLine, initial, businessStateHash, fundsStateHash, 0, true);
    }

    public RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial,
                                long businessStateHash, long fundsStateHash, long initialSequence) {
        this(productLine, initial, businessStateHash, fundsStateHash, initialSequence, true);
    }

    private RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial,
                                 long businessStateHash, long fundsStateHash, long initialSequence,
                                 boolean activateImmediately) {
        if (productLine == null || initial == null || initial.productLine() != productLine) {
            throw new IllegalArgumentException("invalid commit journal state");
        }
        if (initialSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        int capacity = normalizedCapacity(Integer.getInteger(
                "surprising.aeron.commit-journal-capacity",
                Integer.getInteger("surprising.aeron.projection-journal-capacity", 65_536)));
        entries = new AtomicReferenceArray<>(capacity);
        entryBytes = new long[capacity];
        mask = capacity - 1;
        projectionBatchSize = configuredBatchSize(capacity);
        projectionBatchBytes = configuredPositiveLong("surprising.aeron.projection-batch-bytes", 4L << 20);
        batchDelayNanos = configuredPositiveLong("surprising.aeron.projection-batch-delay-nanos", 100_000L);
        capacityBytes = configuredPositiveLong("surprising.aeron.commit-journal-capacity-bytes",
                Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, capacity));
        waitStrategy = WaitStrategy.configured();
        replica = new RuntimeProjectionState(initial, businessStateHash, fundsStateHash, initialSequence);
        publishedSequence = initialSequence;
        consumedSequence = initialSequence;
        requestedSequence = initialSequence;
        projected = new ProjectionVersion(initialSequence, initial, businessStateHash, fundsStateHash);
        initialPoint = new RuntimeProjectionPoint(initialSequence, initial);
        projector = Thread.ofPlatform().daemon(true)
                .name("core-commit-projector-" + productLine.name().toLowerCase(java.util.Locale.ROOT))
                .unstarted(this::runProjector);
        if (activateImmediately) activate();
    }

    public static RuntimeCommitJournal passive(ProductLine productLine, TradingCoreState initial,
                                               long businessStateHash, long fundsStateHash,
                                               long initialSequence) {
        return new RuntimeCommitJournal(productLine, initial, businessStateHash, fundsStateHash,
                initialSequence, false);
    }

    public void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed commit journal");
        activated = true;
        projector.start();
    }

    public boolean activated() { return activated; }

    private long backlogBytes() {
        return Math.subtractExact(publishedBytes, consumedBytes);
    }

    public AdmissionReservation reserveAdmission(int entriesRequired) {
        return reserveAdmission(entriesRequired, Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, entriesRequired));
    }

    public AdmissionReservation reserveAdmission(int entriesRequired, long bytesRequired) {
        requireHealthy();
        if (entriesRequired < 1 || bytesRequired < 1) {
            throw new IllegalArgumentException("journal reservation must be positive");
        }
        long backlog = publishedSequence - consumedSequence;
        if (entriesRequired > entries.length() - backlog - reservedEntries
                || bytesRequired > capacityBytes - backlogBytes() - reservedBytes) {
            rejectionCount++;
            throw new CoreStateRejectedException("MATCHING_BACKPRESSURE", "commit journal backlog is full");
        }
        reservedEntries = Math.addExact(reservedEntries, entriesRequired);
        reservedBytes = Math.addExact(reservedBytes, bytesRequired);
        return new AdmissionReservation(this, entriesRequired, bytesRequired);
    }

    public void release(AdmissionReservation reservation) {
        validateReservation(reservation);
        reservedEntries = Math.subtractExact(reservedEntries, reservation.remaining);
        reservedBytes = Math.subtractExact(reservedBytes, reservation.remainingBytes);
        reservation.remaining = 0;
        reservation.remainingBytes = 0;
        reservation.closed = true;
    }

    public long publish(RuntimeCommitPatch patch, long businessStateHash, long fundsStateHash) {
        AdmissionReservation reservation = reserveAdmission(1);
        try {
            return publish(reservation, patch, businessStateHash, fundsStateHash);
        } finally {
            if (!reservation.closed) release(reservation);
        }
    }

    public long publish(AdmissionReservation reservation, RuntimeCommitPatch patch,
                        long businessStateHash, long fundsStateHash) {
        validateReservation(reservation);
        long next = Math.incrementExact(publishedSequence);
        if (patch == null || patch.sequence() != next
                || businessStateHash != patch.businessStateHash() || fundsStateHash != patch.fundsStateHash()) {
            throw new IllegalStateException("invalid commit journal publication");
        }
        int index = (int) (next & mask);
        if (entries.get(index) != null) {
            poison(new IllegalStateException("commit journal live slot overwrite"));
            throw new IllegalStateException("commit journal slot was not reclaimed", failure);
        }
        long patchBytes = estimatedBytes(patch);
        if (patchBytes > reservation.nextSliceByteAllowance()) {
            throw new IllegalStateException("commit patch exceeded per-slice admission byte reservation");
        }
        entryBytes[index] = patchBytes;
        entries.set(index, patch);
        reservation.remaining--;
        reservation.remainingBytes -= patchBytes;
        reservation.consumedSlices++;
        reservedEntries--;
        reservedBytes -= patchBytes;
        publishedBytes = Math.addExact(publishedBytes, patchBytes);
        long currentBacklogBytes = backlogBytes();
        if (reservation.remaining == 0) {
            reservedBytes -= reservation.remainingBytes;
            reservation.remainingBytes = 0;
            reservation.closed = true;
        }
        maxBacklog = Math.max(maxBacklog, next - consumedSequence);
        maxBacklogBytes = Math.max(maxBacklogBytes, currentBacklogBytes);
        publishedSequence = next;
        LockSupport.unpark(projector);
        return next;
    }

    public PublishReservation reservePublish(long sequence) {
        if (sequence != Math.incrementExact(publishedSequence)) {
            throw new IllegalStateException("commit journal sequence gap");
        }
        return new PublishReservation(reserveAdmission(1), sequence);
    }

    public long publish(PublishReservation reservation, RuntimeCommitPatch patch,
                        long businessStateHash, long fundsStateHash) {
        if (reservation == null || reservation.sequence != Math.incrementExact(publishedSequence)) {
            throw new IllegalStateException("invalid commit journal reservation");
        }
        return publish(reservation.admission, patch, businessStateHash, fundsStateHash);
    }

    public void release(PublishReservation reservation) {
        if (reservation == null) throw new IllegalArgumentException("reservation is required");
        release(reservation.admission);
    }

    public void preflightPublish(RuntimeCommitPatch patch) {
        PublishReservation reservation = reservePublish(patch == null ? -1 : patch.sequence());
        release(reservation);
    }

    public boolean hasCapacityFor(int additionalEntries) {
        requireHealthy();
        if (additionalEntries <= 0
                || additionalEntries > entries.length()
                - (publishedSequence - consumedSequence) - reservedEntries) {
            return false;
        }
        long additionalBytes = Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, additionalEntries);
        return additionalBytes <= capacityBytes - backlogBytes() - reservedBytes;
    }

    public long publishedSequence() { return publishedSequence; }
    public long projectedSequence() { return projected.sequence(); }
    public boolean hasOutstandingReservation() { return reservedEntries != 0 || reservedBytes != 0; }
    public long lag() { return Math.subtractExact(publishedSequence, consumedSequence); }
    public RuntimeProjectionPoint initialPoint() { return initialPoint; }
    public long projectionFreezeCount() { return replica.freezeCount(); }

    public void rebaseInitialBusinessStateHash(long expectedBefore, long after) {
        if (failure != null) throw new IllegalStateException("runtime commit journal failed", failure);
        if (closed) throw new IllegalStateException("runtime commit journal is closed");
        ProjectionVersion current = projected;
        if (publishedSequence != 0 || consumedSequence != 0 || current.sequence() != 0
                || current.businessStateHash() != expectedBefore) {
            throw new IllegalStateException("commit journal is past its initial projection");
        }
        replica.rebaseInitialBusinessStateHash(expectedBefore, after);
        projected = new ProjectionVersion(0, current.state(), after, current.fundsStateHash());
    }
    boolean projectorAlive() { return projector.isAlive(); }
    void failReplicaAfterMutationsForTest(long sequence, int mutationCount) {
        replica.failAfterMutationsForTest(sequence, mutationCount);
    }

    public void blockProjectorForTest(CountDownLatch entered, CountDownLatch release) {
        if (entered == null || release == null) throw new IllegalArgumentException("projector gate is required");
        projectorGate = new ProjectorGate(entered, release);
        LockSupport.unpark(projector);
    }

    void signalProjectionWaiterForTest(CountDownLatch entered) {
        if (entered == null) throw new IllegalArgumentException("projection waiter latch is required");
        projectionWaiterEntered = entered;
    }

    public Metrics metrics() {
        return new Metrics(lag(), maxBacklog, closed ? lag() : -1, batchCount, batchItems, batchBytes,
                backlogBytes(), maxBacklogBytes, waitStrategy, waitCount, rejectionCount, errorCount,
                timeoutCount, reservedEntries, reservedBytes);
    }

    public ProjectionVersion current() {
        requireHealthy();
        return projected;
    }

    public TradingCoreState await(RuntimeProjectionPoint point) {
        return await(point, System.nanoTime() + DEFAULT_AWAIT_NANOS);
    }

    public TradingCoreState await(RuntimeProjectionPoint point, long deadlineNanos) {
        if (point == null || point.sequence() != publishedSequence || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid runtime projection point fence");
        }
        if (point.state() != null) return point.state();
        requestProjection(point.sequence());
        while (!point.projected()) {
            requireHealthy();
            if (System.nanoTime() >= deadlineNanos) {
                timeoutCount++;
                throw new IllegalStateException("runtime projection point timed out");
            }
            awaitWork();
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("runtime projection wait was interrupted");
            }
        }
        return point.state();
    }

    public ProjectionVersion await(long sequence, long deadlineNanos, boolean verifyHashes) {
        if (sequence < 0 || sequence != publishedSequence || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid projection fence");
        }
        requestProjection(sequence);
        while (projected.sequence() < sequence) {
            requireHealthy();
            if (System.nanoTime() >= deadlineNanos) {
                timeoutCount++;
                throw new IllegalStateException("projection fence timed out");
            }
            awaitWork();
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("projection fence wait was interrupted");
            }
        }
        ProjectionVersion result = projected;
        if (verifyHashes) verifyHashes(result);
        return result;
    }

    public void requestProjection(long sequence) {
        requireHealthy();
        if (sequence < 0 || sequence != publishedSequence) {
            throw new IllegalArgumentException("invalid requested projection sequence");
        }
        if (sequence > requestedSequence) requestedSequence = sequence;
        LockSupport.unpark(projector);
    }

    private void runProjector() {
        try {
            long firstPendingNanos = 0;
            while (!closed || consumedSequence < publishedSequence) {
                awaitProjectorGate();
                freezeRequestedState();
                long available = publishedSequence - consumedSequence;
                if (available == 0) {
                    firstPendingNanos = 0;
                    awaitWork();
                    continue;
                }
                if (firstPendingNanos == 0) firstPendingNanos = System.nanoTime();
                boolean forced = closed || requestedSequence > consumedSequence
                        || System.nanoTime() - firstPendingNanos >= batchDelayNanos;
                if (!forced && available < projectionBatchSize) {
                    awaitWork();
                    continue;
                }
                int items = 0;
                long bytes = 0;
                while (items < projectionBatchSize && consumedSequence < publishedSequence) {
                    long sequence = Math.incrementExact(consumedSequence);
                    int index = (int) (sequence & mask);
                    RuntimeCommitPatch patch = entries.get(index);
                    if (patch == null) break;
                    long patchBytes = entryBytes[index];
                    if (items > 0 && bytes + patchBytes > projectionBatchBytes) break;
                    replica.apply(patch);
                    lastAppliedPatch = patch;
                    lastAppliedPatch.projectionPoint().completeSequence();
                    entryBytes[index] = 0;
                    entries.set(index, null);
                    consumedBytes = Math.addExact(consumedBytes, patchBytes);
                    consumedSequence = sequence;
                    items++;
                    bytes = Math.addExact(bytes, patchBytes);
                    freezeRequestedState();
                }
                if (items > 0) {
                    batchCount++;
                    batchItems += items;
                    batchBytes = Math.addExact(batchBytes, bytes);
                    firstPendingNanos = 0;
                }
            }
            requestedSequence = publishedSequence;
            freezeRequestedState();
        } catch (Throwable projectorFailure) {
            try {
                freezeLastCompleteAfterFailure();
            } catch (Throwable freezeFailure) {
                projectorFailure.addSuppressed(freezeFailure);
            }
            poison(projectorFailure);
        }
    }

    private void freezeRequestedState() {
        long requested = requestedSequence;
        if (requested <= projected.sequence() || requested != replica.sequence()) return;
        publishFrozenState(replica.sequence(), replica.freeze(replica.sequence()));
    }

    private void freezeLastCompleteAfterFailure() {
        long applied = replica.sequence();
        if (applied > projected.sequence()) {
            publishFrozenState(applied, replica.freezeLastCompleteAfterFailure(applied));
        }
    }

    private void publishFrozenState(long sequence, TradingCoreState state) {
        projected = new ProjectionVersion(sequence, state, replica.businessStateHash(), replica.fundsStateHash());
        if (lastAppliedPatch != null && lastAppliedPatch.sequence() == sequence
                && !lastAppliedPatch.projectionPoint().projected()) {
            lastAppliedPatch.completeProjection(state);
        }
    }

    private void verifyHashes(ProjectionVersion version) {
        long business = RollingBusinessStateHash.compute(version.state());
        long funds = RollingFundsStateHash.compute(version.state());
        if (business != version.businessStateHash() || funds != version.fundsStateHash()) {
            IllegalStateException mismatch = new IllegalStateException("typed projection hash mismatch");
            poison(mismatch);
            throw mismatch;
        }
    }

    private void validateReservation(AdmissionReservation reservation) {
        requireHealthy();
        if (reservation == null || reservation.owner != this || reservation.closed || reservation.remaining < 1) {
            throw new IllegalStateException("invalid or consumed commit admission reservation");
        }
    }

    private void poison(Throwable cause) {
        if (failure == null) {
            failure = cause;
            errorCount++;
        }
        LockSupport.unpark(projector);
    }

    private void requireHealthy() {
        if (failure != null) throw new IllegalStateException("runtime commit journal failed", failure);
        if (closed) throw new IllegalStateException("runtime commit journal is closed");
        if (!activated) throw new IllegalStateException("runtime commit journal is not activated");
    }

    private void awaitWork() {
        waitCount++;
        CountDownLatch entered = projectionWaiterEntered;
        if (entered != null && Thread.currentThread() != projector) {
            projectionWaiterEntered = null;
            entered.countDown();
        }
        waitStrategy.idle(this);
    }

    private void awaitProjectorGate() throws InterruptedException {
        ProjectorGate gate = projectorGate;
        if (gate == null) return;
        gate.entered.countDown();
        if (!gate.release.await(DEFAULT_AWAIT_NANOS, TimeUnit.NANOSECONDS)) {
            throw new IllegalStateException("test projector gate timed out");
        }
        projectorGate = null;
    }

    @Override
    public void close() {
        requestedSequence = publishedSequence;
        closed = true;
        LockSupport.unpark(projector);
        boolean interrupted = false;
        long deadline = System.nanoTime() + DEFAULT_AWAIT_NANOS;
        while (projector.isAlive() && System.nanoTime() < deadline) {
            try {
                projector.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (projector.isAlive()) {
            projector.interrupt();
            timeoutCount++;
            throw new IllegalStateException("runtime commit projector did not terminate");
        }
        if (failure != null || consumedSequence != publishedSequence || projected.sequence() != publishedSequence
                || reservedEntries != 0 || reservedBytes != 0 || backlogBytes() != 0) {
            throw new IllegalStateException("runtime commit journal did not drain on close", failure);
        }
    }

    private static int normalizedCapacity(int requested) {
        if (requested < MIN_CAPACITY || requested > MAX_CAPACITY) {
            throw new IllegalArgumentException("commit journal capacity is outside supported bounds");
        }
        int value = 1;
        while (value < requested) value <<= 1;
        return value;
    }

    private static int configuredBatchSize(int capacity) {
        int size = Integer.getInteger("surprising.aeron.projection-batch-size", 1);
        if (size < 1 || size > MAX_PROJECTION_BATCH || size > capacity) {
            throw new IllegalArgumentException("projection batch size is outside supported bounds");
        }
        return size;
    }

    private static long configuredPositiveLong(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long estimatedBytes(RuntimeCommitPatch patch) {
        return 256L + 128L * patch.ownerGroups().size() + 96L * patch.fundsPostings().size()
                + 80L * patch.matcherEvidence().size() + 32L * patch.coreFactItemCount();
    }

    public static long maxReservedPatchBytes() { return MAX_RESERVED_PATCH_BYTES; }

    public static final class AdmissionReservation {
        private final RuntimeCommitJournal owner;
        private int remaining;
        private long remainingBytes;
        private final long bytesPerSlice;
        private final int extraByteSlices;
        private int consumedSlices;
        private boolean closed;

        private AdmissionReservation(RuntimeCommitJournal owner, int remaining, long remainingBytes) {
            this.owner = owner;
            this.remaining = remaining;
            this.remainingBytes = remainingBytes;
            bytesPerSlice = remainingBytes / remaining;
            extraByteSlices = Math.toIntExact(remainingBytes % remaining);
        }

        public int remaining() { return remaining; }

        private long nextSliceByteAllowance() {
            return bytesPerSlice + (consumedSlices < extraByteSlices ? 1 : 0);
        }
    }

    public static final class PublishReservation {
        private final AdmissionReservation admission;
        private final long sequence;

        private PublishReservation(AdmissionReservation admission, long sequence) {
            this.admission = admission;
            this.sequence = sequence;
        }
    }

    public enum WaitStrategy {
        BUSY_SPIN {
            @Override void idle(Object blocker) { Thread.onSpinWait(); }
        },
        YIELDING {
            @Override void idle(Object blocker) { Thread.yield(); }
        },
        PARKING {
            @Override void idle(Object blocker) { LockSupport.parkNanos(blocker, IDLE_PARK_NANOS); }
        };

        abstract void idle(Object blocker);

        static WaitStrategy configured() {
            if (Boolean.getBoolean("surprising.aeron.projection-busy-spin")) return BUSY_SPIN;
            String configured = System.getProperty("surprising.aeron.projection-wait-strategy", "PARKING");
            return valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    private record ProjectorGate(CountDownLatch entered, CountDownLatch release) { }

    public record ProjectionVersion(long sequence, TradingCoreState state,
                                    long businessStateHash, long fundsStateHash) {
        public ProjectionVersion {
            if (sequence < 0 || state == null) throw new IllegalArgumentException("invalid projection version");
        }
    }

    public record Metrics(long currentBacklog, long maxBacklog, long endBacklog,
                          long batchCount, long batchItems, long batchBytes,
                          long currentBacklogBytes, long maxBacklogBytes,
                          WaitStrategy waitStrategy, long waitCount, long rejectionCount,
                          long errorCount, long timeoutCount, long reservedEntries, long reservedBytes) { }
}
