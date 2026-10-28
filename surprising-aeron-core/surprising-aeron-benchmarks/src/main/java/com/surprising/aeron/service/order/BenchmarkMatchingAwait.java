package com.surprising.aeron.service.order;

import com.surprising.aeron.service.core.TradingCoreRuntime;

/** 独立基准边界等待适配器，不进入生产服务包。 */
public final class BenchmarkMatchingAwait {
    public static com.surprising.aeron.service.matcher.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence) {
        return awaitMatchingResult(state, sequence, TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
    }

    public static com.surprising.aeron.service.matcher.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence, long timeoutNanos) {
        if (state.fatalFailure() != null) return null;
        if (timeoutNanos <= 0) return null;
        long deadline = System.nanoTime() + timeoutNanos;
        CommandSlot pending = state.pendingMatching().get(sequence);
        while (pending != null && placeAdmissionOutstanding(pending)
                && !state.matchingFlow.hasPendingMatchingRejection(sequence) && System.nanoTime() < deadline) {
            state.matchingProgress.progressPlaceBatchAdmissions();
            if (placeAdmissionOutstanding(pending)) Thread.onSpinWait();
        }
        if (state.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
        int idle = 0;
        while (state.pendingMatching().contains(sequence) && System.nanoTime() < deadline) {
            state.matchingProgress.drainMatchingCompletions();
            if (state.matchingFlow.hasPendingMatchingRejection(sequence)) return null;
            var result = BenchmarkMatchingAwait.takeMatchingResult(state, sequence);
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
    /** 读取现有槽内事实，供测试/基准等待使用；不执行结算或提交。 */
    public static com.surprising.aeron.service.matcher.MatchingResult takeMatchingResult(
            TradingCoreRuntime owner, long sequence) {
        if (owner.fatalFailure() != null) return null;
        CommandSlot pending = owner.pendingMatching().get(sequence);
        if (pending == null) return null;
        if (pending.matchingResult() != null) return pending.matchingResult();
        var direct = pending.settlementEvent();
        if (direct == null && pending.orderBatch != null) {
            direct = pending.orderBatch.itemSettlementEvent != null
                    ? pending.orderBatch.itemSettlementEvent : pending.orderBatch.settlementEvent;
        }
        if (direct != null && direct.direct()) return direct.ready() ? direct.directResult() : null;
        return pending.takeMatchingCompletion();
    }

    private static boolean placeAdmissionOutstanding(CommandSlot pending) {
        var batch = pending.orderBatch;
        return batch != null && batch.placeBatchAdmissionEvent != null && !pending.isMatchingSubmitted();
    }
}
