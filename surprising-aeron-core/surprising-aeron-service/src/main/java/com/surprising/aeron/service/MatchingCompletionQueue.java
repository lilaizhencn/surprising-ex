package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

final class MatchingCompletionQueue {

    private final ManyToOneConcurrentArrayQueue<CoreMatchingResult> queue;
    private final AtomicBoolean overflowed = new AtomicBoolean();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private final AtomicInteger inFlightSubmissions = new AtomicInteger();
    private final AtomicLong publicationCursor = new AtomicLong();
    private final int capacity;
    private volatile Thread waiter;

    MatchingCompletionQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("matching completion capacity must be positive");
        this.capacity = capacity;
        queue = new ManyToOneConcurrentArrayQueue<>(capacity);
    }

    boolean offer(CoreMatchingResult result) {
        if (result == null || result.nativeCommand().coreSequence() <= 0) {
            throw new IllegalArgumentException("matching completion must carry coreSequence");
        }
        if (queue.offer(result)) {
            highWaterMark.accumulateAndGet(queue.size(), Math::max);
            publicationCursor.incrementAndGet();
            signalWaiter();
            return true;
        }
        overflowed.set(true);
        return false;
    }

    boolean consumeOverflow() {
        return overflowed.getAndSet(false);
    }

    CoreMatchingResult poll() {
        return queue.poll();
    }

    long publicationCursor() {
        return publicationCursor.get();
    }

    boolean awaitPublication(long observedCursor, long timeoutNanos) {
        if (timeoutNanos <= 0 || publicationCursor.get() != observedCursor) {
            return publicationCursor.get() != observedCursor;
        }
        Thread current = Thread.currentThread();
        waiter = current;
        try {
            if (publicationCursor.get() == observedCursor) {
                LockSupport.parkNanos(this, timeoutNanos);
            }
            return publicationCursor.get() != observedCursor;
        } finally {
            if (waiter == current) waiter = null;
        }
    }

    void submissionStarted() {
        inFlightSubmissions.incrementAndGet();
    }

    void submissionCompleted() {
        int remaining = inFlightSubmissions.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("matching submission count underflow");
        if (remaining == 0) signalWaiter();
    }

    boolean awaitQuiescence(long timeoutNanos) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (inFlightSubmissions.get() != 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            Thread current = Thread.currentThread();
            waiter = current;
            try {
                if (inFlightSubmissions.get() != 0) LockSupport.parkNanos(this, remaining);
            } finally {
                if (waiter == current) waiter = null;
            }
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void signalWaiter() {
        Thread currentWaiter = waiter;
        if (currentWaiter != null) LockSupport.unpark(currentWaiter);
    }

    void clear() {
        queue.clear();
        overflowed.set(false);
    }

    int depth() { return queue.size(); }
    int capacity() { return capacity; }
    int highWaterMark() { return highWaterMark.get(); }
    int inFlightSubmissions() { return inFlightSubmissions.get(); }
}
