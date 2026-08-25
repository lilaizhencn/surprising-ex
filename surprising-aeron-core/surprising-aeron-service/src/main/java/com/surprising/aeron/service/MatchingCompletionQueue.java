package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import java.util.concurrent.atomic.AtomicBoolean;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

final class MatchingCompletionQueue {

    private final ManyToOneConcurrentArrayQueue<Completion> queue;
    private final AtomicBoolean overflowed = new AtomicBoolean();

    MatchingCompletionQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("matching completion capacity must be positive");
        queue = new ManyToOneConcurrentArrayQueue<>(capacity);
    }

    boolean offer(long sequence, CoreMatchingResult result) {
        if (queue.offer(new Completion(sequence, result))) return true;
        overflowed.set(true);
        return false;
    }

    boolean consumeOverflow() {
        return overflowed.getAndSet(false);
    }

    Completion poll() {
        return queue.poll();
    }

    void clear() {
        queue.clear();
        overflowed.set(false);
    }

    record Completion(long sequence, CoreMatchingResult result) {
        Completion {
            if (sequence <= 0 || result == null) throw new IllegalArgumentException("invalid matching completion");
        }
    }
}
