package com.surprising.aeron.service.execution;

/** 独立基准边界等待适配器，不进入生产服务包。 */
final class BenchmarkMatchingAwait {
    static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence) {
        return awaitMatchingResult(state, sequence, TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
    }

    static com.surprising.aeron.service.matching.CoreMatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence, long timeoutNanos) {
        if (state.fatalFailure != null) return null;
        if (timeoutNanos <= 0) return null;
        long deadline = System.nanoTime() + timeoutNanos;
        PendingMatching pending = state.pendingMatching.get(sequence);
        while (pending != null && state.placeAdmissionOutstanding(pending)
                && !state.hasPendingMatchingRejection(sequence) && System.nanoTime() < deadline) {
            state.progressPlaceAdmissions();
            if (state.placeAdmissionOutstanding(pending)) Thread.onSpinWait();
        }
        if (state.hasPendingMatchingRejection(sequence)) return null;
        int idle = 0;
        while (state.pendingMatching.contains(sequence) && System.nanoTime() < deadline) {
            state.drainMatchingCompletions();
            if (state.hasPendingMatchingRejection(sequence)) return null;
            LaneCommandContextRing.Context context = state.laneCommandContexts.required(sequence);
            com.surprising.aeron.service.matching.CoreMatchingResult result = context.matchingResult();
            if (result == null) result = context.takeMatchingCompletion();
            if (result != null) return result;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) break;
            if (idle++ < 1_024) {
                Thread.onSpinWait();
            } else {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        state, Math.min(remainingNanos, 1_000L));
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("matching completion wait was interrupted");
                }
            }
        }
        return null;
    }
}
