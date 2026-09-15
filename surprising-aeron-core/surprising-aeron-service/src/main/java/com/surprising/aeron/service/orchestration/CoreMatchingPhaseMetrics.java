package com.surprising.aeron.service.orchestration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class CoreMatchingPhaseMetrics {

    @jdk.jfr.Name("surprising.SettlementLatency")
    @jdk.jfr.Label("Matcher publication through ordered commit")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class SettlementLatency extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(SettlementLatency.class);
        public String commandType;
        public long sequence;
        public int lanes;
        public long matcherToLastLaneStartNanos;
        public long maxLaneExecutionNanos;
        public long matcherToLanesCompleteNanos;
        public long lanesCompleteToOwnerNanos;
    }

    static SettlementLatency beginSettlement(CommandSlot pending, long sequence) {
        if (pending == null) return null;
        var settlement = pending.orderBatch == null ? pending.settlementEvent() : pending.orderBatch.settlementEvent;
        if (settlement == null || !settlement.complete() || settlement.matcherPublishedNanos() == 0) return null;
        if (!SettlementLatency.TYPE.isEnabled()) return null;
        long observed = System.nanoTime();
        var event = new SettlementLatency();
        event.commandType = pending.command().header().messageType().name();
        event.sequence = sequence;
        long mask = settlement.completedLaneMask();
        if (mask == 0) return null;
        event.lanes = Long.bitCount(mask);
        long lastStart = Long.MIN_VALUE, lastFinish = Long.MIN_VALUE;
        while (mask != 0) {
            int lane = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            long start = settlement.laneStartedNanos(lane), finish = settlement.laneFinishedNanos(lane);
            lastStart = Math.max(lastStart, start);
            lastFinish = Math.max(lastFinish, finish);
            event.maxLaneExecutionNanos = Math.max(event.maxLaneExecutionNanos, finish - start);
        }
        event.matcherToLastLaneStartNanos = lastStart - settlement.matcherPublishedNanos();
        event.matcherToLanesCompleteNanos = lastFinish - settlement.matcherPublishedNanos();
        event.lanesCompleteToOwnerNanos = observed - lastFinish;
        event.begin();
        return event;
    }

    /** Sparse wall-clock samples at existing thread/queue boundaries; never business state. */
    @jdk.jfr.Name("surprising.CommandBoundaryLatency")
    @jdk.jfr.Label("Command queue and admission latency")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class CommandBoundaryLatency extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(CommandBoundaryLatency.class);
        public String stage;
        public String commandType;
        public long commandIdHigh, commandIdLow;
        public long elapsedNanos;
    }

    static long sampleStart(com.surprising.aeron.protocol.CoreMessageHeader header) {
        if (!com.surprising.aeron.service.state.MatcherSettlementEvent.LATENCY_DIAGNOSTICS
                || (header.commandId().hashCode() & 63) != 0) return 0;
        return CommandBoundaryLatency.TYPE.isEnabled() ? System.nanoTime() : 0;
    }

    static void recordBoundary(String stage, com.surprising.aeron.protocol.CoreMessageHeader header, long start) {
        if (start == 0) return;
        long elapsed = System.nanoTime() - start;
        var event = new CommandBoundaryLatency();
        event.stage = stage;
        event.commandType = header.messageType().name();
        event.commandIdHigh = header.commandId().getMostSignificantBits();
        event.commandIdLow = header.commandId().getLeastSignificantBits();
        event.elapsedNanos = elapsed;
        event.commit();
    }

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
