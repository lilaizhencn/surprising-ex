package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;

/**
 * Owner-thread commit admission and sequence journal.
 *
 * <p>The authoritative mutable state already lives on the Product Core owner. Keeping a second
 * {@link RuntimeProjectionState} current for every command duplicated all Map/hash/materialization
 * work and introduced a reverse projection fence. This journal therefore retains only bounded
 * admission, sequencing and diagnostic metadata. A read-only {@link TradingCoreState} is built
 * explicitly by {@link RuntimeStateMaterializer} at a query or snapshot boundary.</p>
 */
public final class RuntimeCommitJournal implements AutoCloseable {

    private static final int MIN_CAPACITY = 1_024;
    private static final int MAX_CAPACITY = 1 << 20;
    private static final long MAX_RESERVED_PATCH_BYTES = 16L << 20;

    private final int capacity;
    private final long capacityBytes;
    private final WaitStrategy waitStrategy;
    private final RuntimeProjectionPoint initialPoint;
    private long publishedSequence;
    private long reservedEntries;
    private long reservedBytes;
    private long batchCount;
    private long batchItems;
    private long batchBytes;
    private long rejectionCount;
    private long errorCount;
    private long timeoutCount;
    private long businessStateHash;
    private long fundsStateHash;
    private boolean activated;
    private boolean closed;
    private int publicationBatchDepth;

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
        capacity = normalizedCapacity(Integer.getInteger(
                "surprising.aeron.commit-journal-capacity",
                Integer.getInteger("surprising.aeron.projection-journal-capacity", 65_536)));
        capacityBytes = configuredPositiveLong("surprising.aeron.commit-journal-capacity-bytes",
                Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, capacity));
        waitStrategy = WaitStrategy.PARKING;
        initialPoint = new RuntimeProjectionPoint(initialSequence, null);
        initialPoint.completeSequence();
        publishedSequence = initialSequence;
        this.businessStateHash = businessStateHash;
        this.fundsStateHash = fundsStateHash;
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
    }

    public boolean activated() { return activated; }

    public AdmissionReservation reserveAdmission(int entriesRequired) {
        return reserveAdmission(entriesRequired, Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, entriesRequired));
    }

    public AdmissionReservation reserveAdmission(int entriesRequired, long bytesRequired) {
        requireHealthy();
        if (entriesRequired < 1 || bytesRequired < 1) {
            throw new IllegalArgumentException("journal reservation must be positive");
        }
        if (entriesRequired > capacity - reservedEntries
                || bytesRequired > capacityBytes - reservedBytes) {
            rejectionCount++;
            throw new CoreStateRejectedException("MATCHING_BACKPRESSURE", "commit admission is full");
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
        long patchBytes = estimatedBytes(patch);
        if (patchBytes > reservation.nextSliceByteAllowance()) {
            throw new IllegalStateException("commit patch exceeded per-slice admission byte reservation");
        }
        reservation.remaining--;
        reservation.remainingBytes -= patchBytes;
        reservation.consumedSlices++;
        reservedEntries--;
        reservedBytes -= patchBytes;
        if (reservation.remaining == 0) {
            reservedBytes -= reservation.remainingBytes;
            reservation.remainingBytes = 0;
            reservation.closed = true;
        }
        publishedSequence = next;
        this.businessStateHash = businessStateHash;
        this.fundsStateHash = fundsStateHash;
        patch.projectionPoint().completeSequence();
        batchCount++;
        batchItems++;
        batchBytes = Math.addExact(batchBytes, patchBytes);
        return next;
    }

    public void beginPublicationBatch() {
        requireHealthy();
        publicationBatchDepth = Math.incrementExact(publicationBatchDepth);
    }

    public void endPublicationBatch() {
        if (publicationBatchDepth <= 0) {
            throw new IllegalStateException("commit journal publication batch is not active");
        }
        publicationBatchDepth--;
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
        if (additionalEntries <= 0 || additionalEntries > capacity - reservedEntries) return false;
        long additionalBytes = Math.multiplyExact(MAX_RESERVED_PATCH_BYTES, additionalEntries);
        return additionalBytes <= capacityBytes - reservedBytes;
    }

    public long publishedSequence() { return publishedSequence; }
    public long projectedSequence() { return publishedSequence; }
    public boolean hasOutstandingReservation() { return reservedEntries != 0 || reservedBytes != 0; }
    public long lag() { return 0; }
    public RuntimeProjectionPoint initialPoint() { return initialPoint; }
    public long projectionFreezeCount() { return 0; }

    public void rebaseInitialBusinessStateHash(long expectedBefore, long after) {
        if (closed) throw new IllegalStateException("runtime commit journal is closed");
        if (publishedSequence != 0 || businessStateHash != expectedBefore) {
            throw new IllegalStateException("commit journal is past its initial sequence");
        }
        businessStateHash = after;
    }

    boolean projectorAlive() { return false; }

    public Metrics metrics() {
        return new Metrics(0, 0, 0, batchCount, batchItems, batchBytes,
                0, 0, waitStrategy, 0, rejectionCount, errorCount, timeoutCount,
                reservedEntries, reservedBytes);
    }

    public ProjectionVersion current() {
        requireHealthy();
        return new ProjectionVersion(publishedSequence, null, businessStateHash, fundsStateHash);
    }

    public void assertHealthy() { requireHealthy(); }

    public TradingCoreState await(RuntimeProjectionPoint point) {
        return await(point, Long.MAX_VALUE);
    }

    public TradingCoreState await(RuntimeProjectionPoint point, long deadlineNanos) {
        if (point == null || point.sequence() != publishedSequence || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid runtime projection point fence");
        }
        if (point.state() != null) return point.state();
        throw new UnsupportedOperationException(
                "per-command projection was removed; materialize authoritative runtime at a read fence");
    }

    public ProjectionVersion await(long sequence, long deadlineNanos, boolean verifyHashes) {
        if (sequence < 0 || sequence != publishedSequence || deadlineNanos <= 0) {
            throw new IllegalArgumentException("invalid projection fence");
        }
        if (sequence != 0) {
            throw new UnsupportedOperationException(
                    "per-command projection was removed; materialize authoritative runtime at a read fence");
        }
        return current();
    }

    public void requestProjection(long sequence) {
        requireHealthy();
        if (sequence < 0 || sequence != publishedSequence) {
            throw new IllegalArgumentException("invalid requested projection sequence");
        }
    }

    private void validateReservation(AdmissionReservation reservation) {
        requireHealthy();
        if (reservation == null || reservation.owner != this || reservation.closed || reservation.remaining < 1) {
            throw new IllegalStateException("invalid or consumed commit admission reservation");
        }
    }

    private void requireHealthy() {
        if (closed) throw new IllegalStateException("runtime commit journal is closed");
        if (!activated) throw new IllegalStateException("runtime commit journal is not activated");
    }

    @Override
    public void close() {
        if (closed) return;
        if (publicationBatchDepth != 0 || reservedEntries != 0 || reservedBytes != 0) {
            throw new IllegalStateException("runtime commit journal closed with outstanding admission");
        }
        closed = true;
    }

    private static int normalizedCapacity(int requested) {
        if (requested < MIN_CAPACITY || requested > MAX_CAPACITY) {
            throw new IllegalArgumentException("commit journal capacity is outside supported bounds");
        }
        int value = 1;
        while (value < requested) value <<= 1;
        return value;
    }

    private static long configuredPositiveLong(String name, long defaultValue) {
        long value = Long.getLong(name, defaultValue);
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long estimatedBytes(RuntimeCommitPatch patch) {
        return 384L + 128L * patch.accountLaneGroups().size() + 96L * patch.fundsPostings().size()
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
        BUSY_SPIN,
        YIELDING,
        PARKING;

    }

    public record ProjectionVersion(long sequence, TradingCoreState state,
                                    long businessStateHash, long fundsStateHash) {
        public ProjectionVersion {
            if (sequence < 0) throw new IllegalArgumentException("invalid projection version");
        }
    }

    public record Metrics(long currentBacklog, long maxBacklog, long endBacklog,
                          long batchCount, long batchItems, long batchBytes,
                          long currentBacklogBytes, long maxBacklogBytes,
                          WaitStrategy waitStrategy, long waitCount, long rejectionCount,
                          long errorCount, long timeoutCount, long reservedEntries, long reservedBytes) { }
}
