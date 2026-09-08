package com.surprising.aeron.benchmarks.workload;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Real source-to-publication delay for synthetic prices; never backdates or changes Core freshness. */
final class GeneratedPriceClock {
    static long timestamp() {
        long timestamp = System.currentTimeMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("price publication interrupted");
            LockSupport.parkNanos(remaining);
        }
        return timestamp;
    }
}
