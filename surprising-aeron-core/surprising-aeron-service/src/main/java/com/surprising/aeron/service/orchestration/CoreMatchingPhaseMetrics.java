package com.surprising.aeron.service.orchestration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class CoreMatchingPhaseMetrics {

    /** Owner 本地非空 FIFO 调度轮次的稀疏样本，不持有业务命令或状态副本。 */
    @jdk.jfr.Name("surprising.OwnerTurn")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class OwnerTurn extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(OwnerTurn.class);
        public int retired;
        public int admitted;
        public int headRechecks, readyHeadRechecks;
        public int windowAtStart;
        public int windowAtEnd;
        public boolean headWait;
        public boolean budgetExhausted;
    }

    private long nonemptyOwnerTurns;

    OwnerTurn sampleOwnerTurn(int windowSize) {
        if (!com.surprising.aeron.service.state.MatcherSettlementEvent.LATENCY_DIAGNOSTICS
                || windowSize == 0 || (++nonemptyOwnerTurns & 63) != 0
                || !OwnerTurn.TYPE.isEnabled()) return null;
        var event = new OwnerTurn();
        event.windowAtStart = windowSize;
        event.begin();
        return event;
    }

    /** Exclusive substeps of Owner publication; sequence is the projection sequence, not matching. */
    @jdk.jfr.Name("surprising.OwnerPublication")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class OwnerPublication extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(OwnerPublication.class);
        public long publicationSequence;
        public long fundsNanos, realtimeCaptureNanos, indexesNanos, journalNanos, clearNanos, totalNanos;
        public boolean completed;
    }

    static OwnerPublication sampleOwnerPublication(long sequence) {
        if (!com.surprising.aeron.service.state.MatcherSettlementEvent.LATENCY_DIAGNOSTICS
                || (Long.hashCode(sequence * 0x9e3779b97f4a7c15L) & 63) != 0
                || !OwnerPublication.TYPE.isEnabled()) return null;
        var event = new OwnerPublication();
        event.publicationSequence = sequence;
        event.begin();
        return event;
    }

    /** Sparse head residence, owned only by Owner and discarded on retirement. */
    @jdk.jfr.Name("surprising.OwnerHead")
    @jdk.jfr.Category("Surprising Core")
    @jdk.jfr.StackTrace(false)
    static final class OwnerHead extends jdk.jfr.Event {
        private static final jdk.jfr.EventType TYPE = jdk.jfr.EventType.getEventType(OwnerHead.class);
        public String commandType;
        public long sequence, commandIdHigh, commandIdLow;
        public long observedNanos;
        public boolean afterPredecessor;
        public long retiredNanos, attemptNanos, admissionWhileWaitingNanos;
        public long admissionAfterLaneFinishNanos;
        public int admittedAfterLaneFinish;
        public long lastAttemptNanos, maxAttemptGapNanos;
        public int attempts, unreadyAttempts, admittedWhileWaiting;
        public String firstWaitReason, lastWaitReason;
        public boolean completed;
    }

    static void recordOwnerHead(ClusterCommandWindow.Entry head, boolean afterPredecessor) {
        if (!com.surprising.aeron.service.state.MatcherSettlementEvent.LATENCY_DIAGNOSTICS
                || head.sequence == 0
                || (Long.hashCode(head.sequence * 0x9e3779b97f4a7c15L) & 63) != 0
                || !OwnerHead.TYPE.isEnabled()) return;
        var event = new OwnerHead();
        var header = head.request.header();
        event.sequence = head.sequence;
        event.commandType = header.messageType().name();
        event.commandIdHigh = header.commandId().getMostSignificantBits();
        event.commandIdLow = header.commandId().getLeastSignificantBits();
        event.afterPredecessor = afterPredecessor;
        event.observedNanos = System.nanoTime();
        event.begin();
        head.headTiming = event;
    }

    static void finishOwnerHead(ClusterCommandWindow.Entry head) {
        var timing = head.headTiming;
        if (timing == null) return;
        head.headTiming = null;
        timing.retiredNanos = System.nanoTime();
        timing.completed = head.response != null;
        timing.end();
        timing.commit();
    }

    static long beginHeadAttempt(OwnerHead timing) {
        if (timing == null) return 0;
        long now = System.nanoTime();
        if (timing.lastAttemptNanos != 0)
            timing.maxAttemptGapNanos = Math.max(timing.maxAttemptGapNanos, now - timing.lastAttemptNanos);
        timing.lastAttemptNanos = now;
        timing.attempts++;
        return now;
    }

    static void finishHeadAttempt(OwnerHead timing, long started, String waitReason) {
        if (timing == null) return;
        timing.attemptNanos += System.nanoTime() - started;
        if (waitReason != null) {
            timing.unreadyAttempts++;
            if (timing.firstWaitReason == null) timing.firstWaitReason = waitReason;
            timing.lastWaitReason = waitReason;
        }
    }

    static void recordAdmissionWhileWaiting(OwnerHead timing, long started, CommandSlot pending) {
        long finished = System.nanoTime();
        timing.admittedWhileWaiting++;
        timing.admissionWhileWaitingNanos += finished - started;
        if (pending == null) return;
        var settlement = pending.orderBatch == null ? pending.settlementEvent() : pending.orderBatch.settlementEvent;
        if (settlement == null || !settlement.complete() || settlement.matcherPublishedNanos() == 0) return;
        long lanes = settlement.completedLaneMask();
        if (lanes == 0) return;
        long laneFinished = Long.MIN_VALUE;
        while (lanes != 0) {
            int lane = Long.numberOfTrailingZeros(lanes);
            lanes &= lanes - 1;
            laneFinished = Math.max(laneFinished, settlement.laneFinishedNanos(lane));
        }
        long overlap = finished - Math.max(started, laneFinished);
        if (overlap > 0) {
            timing.admissionAfterLaneFinishNanos += overlap;
            timing.admittedAfterLaneFinish++;
        }
    }

    /** Read-only observation after an unsuccessful attempt, not a readiness predicate. */
    static String headWaitReason(CommandSlot pending) {
        if (pending == null) return "NO_PENDING";
        var batch = pending.orderBatch;
        if (batch != null) {
            if (!batch.activated()) return "BATCH_ACTIVATION";
            if (batch.placeBatchAdmissionEvent != null && !batch.placeBatchAdmissionEvent.complete())
                return "BATCH_ADMISSION";
            if (batch.itemAdmission != null) return "ITEM_ADMISSION_CONTINUATION";
            if (batch.itemSettlementEvent != null && !batch.itemSettlementEvent.complete()) return "ITEM_SETTLEMENT";
            if (batch.laneCommitEvent != null && !batch.laneCommitEvent.complete()) return "LANE_COMMIT";
            if (batch.cancelEvent != null && !batch.cancelEvent.complete()) return "LANE_CANCEL";
        }
        if (pending.placeAdmission() != null && !pending.placeAdmission().complete()) return "PLACE_ADMISSION";
        var settlement = batch == null ? pending.settlementEvent() : batch.settlementEvent;
        if (settlement != null && settlement.direct()) {
            if (!settlement.ready()) return "MATCHER_PUBLICATION";
            if (!settlement.complete()) return "LANE_SETTLEMENT";
            return pending.submittedMatcherShard() != -1 ? "MATCHER_TOKEN_RELEASE" : "READY_FINALIZATION";
        }
        if (pending.hasLaneContinuation() && !pending.laneContinuationComplete()) return "LANE_CONTINUATION";
        if (pending.matchingResult() != null || pending.hasMatchingCompletion()) return "RESULT_FINALIZATION";
        return pending.isMatchingSubmitted() ? "MATCHER_RESULT" : "MATCHER_SUBMISSION";
    }

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
        /** Same-process monotonic timestamps for joining to OwnerHead; never business state. */
        public long commandIdHigh, commandIdLow, lanesCompletedNanos, ownerObservedNanos;
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
        event.commandIdHigh = pending.command().header().commandId().getMostSignificantBits();
        event.commandIdLow = pending.command().header().commandId().getLeastSignificantBits();
        event.ownerObservedNanos = observed;
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
        event.lanesCompletedNanos = lastFinish;
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
