package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

final class MatchingCompletionQueue {

    private final ManyToOneConcurrentArrayQueue<CoreMatchingResult> queue;
    private final AtomicBoolean overflowed = new AtomicBoolean();
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private final int capacity;

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

    void clear() {
        queue.clear();
        overflowed.set(false);
    }

    int depth() { return queue.size(); }
    int capacity() { return capacity; }
    int highWaterMark() { return highWaterMark.get(); }
}
