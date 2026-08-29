package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

final class MatchingCompletionQueue {

    private final AtomicReferenceArray<CoreMatchingResult> slots;
    private final AtomicBoolean overflowed = new AtomicBoolean();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private final AtomicInteger inFlightSubmissions = new AtomicInteger();
    private final AtomicInteger depth = new AtomicInteger();
    private final int mask;
    private final int capacity;
    private volatile Thread waiter;
    private volatile long waiterSequence;

    MatchingCompletionQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("matching completion capacity must be positive");
        this.capacity = normalizedCapacity(capacity);
        mask = this.capacity - 1;
        slots = new AtomicReferenceArray<>(this.capacity);
    }

    boolean offer(CoreMatchingResult result) {
        if (result == null || result.nativeCommand().coreSequence() <= 0) {
            throw new IllegalArgumentException("matching completion must carry coreSequence");
        }
        long sequence = result.nativeCommand().coreSequence();
        int index = index(sequence);
        int currentDepth = depth.incrementAndGet();
        if (slots.compareAndSet(index, null, result)) {
            highWaterMark.accumulateAndGet(currentDepth, Math::max);
            if (currentDepth == 1 || waiterSequence == sequence) signalWaiter();
            return true;
        }
        depth.decrementAndGet();
        overflowed.set(true);
        return false;
    }

    boolean consumeOverflow() {
        return overflowed.getAndSet(false);
    }

    CoreMatchingResult poll(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("matching completion sequence must be positive");
        int index = index(sequence);
        CoreMatchingResult result = slots.get(index);
        if (result == null || result.nativeCommand().coreSequence() != sequence) return null;
        if (!slots.compareAndSet(index, result, null)) return null;
        int remaining = depth.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("matching completion accounting corrupted");
        return result;
    }

    boolean available(long sequence) {
        if (sequence <= 0) return false;
        CoreMatchingResult result = slots.get(index(sequence));
        return result != null && result.nativeCommand().coreSequence() == sequence;
    }

    boolean awaitSequence(long sequence, long timeoutNanos) {
        if (sequence <= 0) throw new IllegalArgumentException("matching completion sequence must be positive");
        if (timeoutNanos <= 0 || available(sequence)) return available(sequence);
        Thread current = Thread.currentThread();
        waiter = current;
        waiterSequence = sequence;
        try {
            if (!available(sequence)) LockSupport.parkNanos(this, timeoutNanos);
            return available(sequence);
        } finally {
            waiterSequence = 0;
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
        for (int index = 0; index < slots.length(); index++) slots.set(index, null);
        depth.set(0);
        overflowed.set(false);
    }

    int depth() { return depth.get(); }
    int capacity() { return capacity; }
    int highWaterMark() { return highWaterMark.get(); }
    int inFlightSubmissions() { return inFlightSubmissions.get(); }

    private int index(long sequence) {
        return (int) sequence & mask;
    }

    private static int normalizedCapacity(int requested) {
        if (requested > 1 << 30) {
            throw new IllegalArgumentException("matching completion capacity is too large");
        }
        int normalized = 1;
        while (normalized < requested) normalized <<= 1;
        return normalized;
    }
}
