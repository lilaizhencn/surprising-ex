package com.surprising.aeron.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class CoreMatchingPhaseMetrics {

    private final Phase prepare = new Phase();
    private final Phase exchange = new Phase();
    private final Phase apply = new Phase();

    void recordPrepare(long nanos) {
        prepare.record(nanos);
    }

    void recordExchange(long nanos) {
        exchange.record(nanos);
    }

    void recordApply(long nanos) {
        apply.record(nanos);
    }

    String reportAndReset() {
        return "prepare=" + prepare.reportAndReset()
                + " exchange=" + exchange.reportAndReset()
                + " apply=" + apply.reportAndReset();
    }

    private static final class Phase {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();

        void record(long nanos) {
            count.increment();
            totalNanos.add(nanos);
            maxNanos.accumulateAndGet(nanos, Math::max);
        }

        String reportAndReset() {
            long samples = count.sumThenReset();
            long total = totalNanos.sumThenReset();
            long max = maxNanos.getAndSet(0L);
            long averageMicros = samples == 0 ? 0 : total / samples / 1_000L;
            return "avgMicros=" + averageMicros + ",maxMicros=" + max / 1_000L + ",count=" + samples;
        }
    }
}
