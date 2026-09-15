package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;

/**
 * Owner-thread commit sequence and diagnostic metadata.
 *
 * <p>The authoritative mutable state already lives on the Product Core owner. Keeping a second
 * {@link RuntimeProjectionState} current for every command duplicated all Map/hash/materialization
 * work and introduced a reverse projection fence. This journal therefore retains only sequencing and diagnostic metadata. A read-only {@link TradingCoreState} is built
 * explicitly by {@link RuntimeStateMaterializer} at a query or snapshot boundary.</p>
 */
public final class RuntimeCommitJournal implements AutoCloseable {

    private static final long MAX_RESERVED_PATCH_BYTES = 16L << 20;

    private final WaitStrategy waitStrategy;
    private final RuntimeProjectionPoint initialPoint;
    private long publishedSequence;
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

    public long publish(RuntimeFactFrame patch, long businessStateHash, long fundsStateHash) {
        requireHealthy();
        return publishCommittedPatch(patch, businessStateHash, fundsStateHash);
    }

    public long publish(long sequence, long businessStateHash, long fundsStateHash) {
        requireHealthy();
        long next = Math.incrementExact(publishedSequence);
        if (sequence != next) throw new IllegalStateException("invalid fact frame publication");
        publishedSequence = next;
        this.businessStateHash = businessStateHash;
        this.fundsStateHash = fundsStateHash;
        batchCount++;
        batchItems++;
        batchBytes = Math.addExact(batchBytes, 64);
        return next;
    }

    private long publishCommittedPatch(RuntimeFactFrame patch,
                                       long businessStateHash,
                                       long fundsStateHash) {
        long next = Math.incrementExact(publishedSequence);
        if (patch == null || patch.sequence() != next
                || businessStateHash != patch.businessStateHash() || fundsStateHash != patch.fundsStateHash()) {
            throw new IllegalStateException("invalid commit journal publication");
        }
        long patchBytes = estimatedBytes(patch);
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

    public long publishedSequence() { return publishedSequence; }
    public long projectedSequence() { return publishedSequence; }
    public long auditBusinessStateHash() { return businessStateHash; }
    public long auditFundsStateHash() { return fundsStateHash; }
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
                0, 0);
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

    private void requireHealthy() {
        if (closed) throw new IllegalStateException("runtime commit journal is closed");
        if (!activated) throw new IllegalStateException("runtime commit journal is not activated");
    }

    @Override
    public void close() {
        if (closed) return;
        if (publicationBatchDepth != 0) {
            throw new IllegalStateException("runtime commit journal closed with an active publication batch");
        }
        closed = true;
    }

    private static long estimatedBytes(RuntimeFactFrame patch) {
        return 384L + 128L * patch.accountLaneGroups().size() + 96L * patch.fundsPostings().size()
                + 80L * patch.matcherEvidence().size() + 32L * patch.coreFactItemCount();
    }

    public static long maxReservedPatchBytes() { return MAX_RESERVED_PATCH_BYTES; }

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
