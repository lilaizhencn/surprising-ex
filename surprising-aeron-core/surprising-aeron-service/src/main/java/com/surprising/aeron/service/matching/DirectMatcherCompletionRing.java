package com.surprising.aeron.service.matching;

import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.cmd.OrderCommand;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded correlation ring for the exchange-core direct submission path.
 * Publishers reserve before publishing, and the single results processor
 * removes the reservation only after it has copied the mutable ring event.
 */
final class DirectMatcherCompletionRing {

    private final AtomicReferenceArray<Pending> slots;
    private final AtomicLong nextCorrelation = new AtomicLong();
    private final int mask;

    DirectMatcherCompletionRing(int requestedCapacity) {
        if (requestedCapacity <= 0) {
            throw new IllegalArgumentException("direct matcher completion capacity must be positive");
        }
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        slots = new AtomicReferenceArray<>(capacity);
        mask = capacity - 1;
    }

    Pending reserve() {
        long correlationId = nextCorrelation.incrementAndGet();
        if (correlationId <= 0) {
            throw new IllegalStateException("direct matcher correlation sequence exhausted");
        }
        Pending pending = new Pending(correlationId);
        int index = index(correlationId);
        if (!slots.compareAndSet(index, null, pending)) {
            throw new IllegalStateException("direct matcher completion ring is exhausted");
        }
        return pending;
    }

    void complete(long correlationId, long nativeSequence, OrderCommand command) {
        int index = index(correlationId);
        Pending pending = slots.get(index);
        if (pending == null || pending.correlationId != correlationId
                || !slots.compareAndSet(index, pending, null)) {
            throw new IllegalStateException(
                    "unknown direct matcher completion correlation " + correlationId);
        }
        try {
            pending.future.complete(CoreMatchingResult.fromNative(
                    MatcherResult.from(nativeSequence, command)));
        } catch (Throwable failure) {
            pending.future.completeExceptionally(failure);
        }
    }

    void fail(Pending pending, Throwable failure) {
        if (pending == null || failure == null) return;
        int index = index(pending.correlationId);
        if (slots.compareAndSet(index, pending, null)) {
            pending.future.completeExceptionally(failure);
        }
    }

    private int index(long correlationId) {
        return (int) correlationId & mask;
    }

    static final class Pending {
        private final long correlationId;
        private final CompletableFuture<CoreMatchingResult> future = new CompletableFuture<>();

        long correlationId() {
            return correlationId;
        }

        CompletableFuture<CoreMatchingResult> future() {
            return future;
        }

        private Pending(long correlationId) {
            this.correlationId = correlationId;
        }
    }
}
