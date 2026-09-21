package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;

/**
 * Owner-thread commit sequence and diagnostic metadata.
 *
 * <p>The authoritative mutable state already lives on the Product Core owner. Keeping a second
 * Keeping a second projected state current for every command duplicated all Map/hash/materialization
 * work and introduced a reverse projection fence. This journal therefore retains only sequencing and recovery hashes. A read-only {@link TradingCoreState} is built
 * explicitly by {@link RuntimeStateMaterializer} at a query or snapshot boundary.</p>
 */
public final class RuntimeCommitJournal implements AutoCloseable {

    private long publishedSequence;
    private boolean activated;
    private boolean closed;
    private int publicationBatchDepth;

    public RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial) {
        this(productLine, initial, 0, true);
    }

    public RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial, long initialSequence) {
        this(productLine, initial, initialSequence, true);
    }

    private RuntimeCommitJournal(ProductLine productLine, TradingCoreState initial, long initialSequence,
                                 boolean activateImmediately) {
        if (productLine == null || initial == null || initial.productLine() != productLine) {
            throw new IllegalArgumentException("invalid commit journal state");
        }
        if (initialSequence < 0) throw new IllegalArgumentException("initial commit sequence is negative");
        publishedSequence = initialSequence;
        if (activateImmediately) activate();
    }

    public static RuntimeCommitJournal passive(ProductLine productLine, TradingCoreState initial,
                                               long initialSequence) {
        return new RuntimeCommitJournal(productLine, initial, initialSequence, false);
    }

    public void activate() {
        if (activated) return;
        if (closed) throw new IllegalStateException("cannot activate closed commit journal");
        activated = true;
    }

    public boolean activated() { return activated; }

    public long publish(long sequence) {
        requireHealthy();
        long next = Math.incrementExact(publishedSequence);
        if (sequence != next) throw new IllegalStateException("invalid fact frame publication");
        publishedSequence = next;
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
    public void assertHealthy() { requireHealthy(); }

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

    public record ProjectionVersion(long sequence, TradingCoreState state,
                                    long businessStateHash, long fundsStateHash) {
        public ProjectionVersion {
            if (sequence < 0) throw new IllegalArgumentException("invalid projection version");
        }
    }

}
