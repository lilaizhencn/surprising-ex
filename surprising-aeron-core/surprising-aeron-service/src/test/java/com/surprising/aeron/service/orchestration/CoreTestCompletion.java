package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.orchestration.ClusterCommandWindow;
import com.surprising.aeron.protocol.CoreResponse;

/** 功能测试的有界等待适配器；生产集群只使用有限轮询推进。 */
final class CoreTestCompletion {
    static CoreResponse applyAsynchronously(TradingCoreRuntime owner,
            com.surprising.aeron.protocol.CoreMessage message) {
        return applyAsynchronously(owner, message, message.header().submittedAtEpochMillis(),
                message.header().sourceSequence());
    }

    static CoreResponse applyAsynchronously(TradingCoreRuntime owner,
            com.surprising.aeron.protocol.CoreMessage message, long timestamp, long position) {
        owner.activate();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var window = new ClusterCommandWindow();
        boolean independent = owner.prepareClusterPipelineScope(message, window);
        while (!independent && owner.requiresOwnerLaneAccessForPreparation(message)
                && !owner.runtimeState.tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Lane handoff timeout");
            Thread.onSpinWait();
        }
        owner.runtimeState.enterAsynchronousCommandScope();
        try {
            CoreResponse response = owner.applyDecodedCommand(message, timestamp, position, window.decodedIfPresent(message), independent);
            while (owner.hasPendingDirectCommand()) {
                response = owner.pollDirectCommand();
                if (System.nanoTime() >= deadline) throw new AssertionError("direct command timeout");
                Thread.onSpinWait();
            }
            long requestedSequence = owner.matchingSequence(message.header().commandId());
            CoreResponse[] completed = {response};
            while (owner.firstPendingMatchingSequence() != 0) {
                owner.commits.commitReadyMatching(64, timestamp, position, false,
                        (sequence, result) -> { if (sequence == requestedSequence) completed[0] = result; });
                if (System.nanoTime() >= deadline) throw new AssertionError("matching command timeout " + message.header().messageType()
                        + " independent=" + independent + " requested=" + requestedSequence
                        + " first=" + owner.firstPendingMatchingSequence()
                        + " control=" + owner.commits.controlPending(requestedSequence)
                        + " completion=" + owner.laneCommandContexts.required(requestedSequence).hasMatchingCompletion()
                        + " result=" + owner.laneCommandContexts.required(requestedSequence).matchingResult()
                        + " submitted=" + owner.pendingMatching(requestedSequence).isMatchingSubmitted());
                Thread.onSpinWait();
            }
            return completed[0];
        } finally {
            owner.runtimeState.exitAsynchronousCommandScope();
            owner.runtimeState.releaseOwnerLaneAccess();
        }
    }

    static CoreResponse completeMatchingSynchronously(TradingCoreRuntime owner, long requestedSequence,
                                               long clusterTimestamp,
                                               long clusterPosition) {
        owner.assertOwner();
        if (requestedSequence < 0 || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("invalid synchronous matching fence");
        }
        CoreResponse requestedResponse = null;
        owner.beginDownstreamPublicationBatch();
        try {
            while (true) {
                owner.drainMatchingCompletions();
                long sequence = requestedSequence != 0 && owner.pendingMatching.contains(requestedSequence)
                        ? requestedSequence : owner.firstPendingMatchingSequence();
                if (sequence == 0) break;
                owner.progressPlaceBatchAdmissions();
                CoreResponse response;
                if (owner.hasPendingMatchingRejection(sequence)) {
                    response = owner.commits.completeRejectedMatching(sequence);
                } else {
                    CommandSlot pending = owner.pendingMatching.get(sequence);
                    com.surprising.aeron.service.matching.MatchingResult matching =
                            pending != null && pending.orderBatch != null && (pending.orderBatch.itemSettlementEvent != null
                                    || pending.orderBatch.itemAdmission != null && pending.orderBatch.activated())
                                    ? pending.orderBatch.lastMatchingResult
                                    : pending != null && (pending.settlementEvent() != null || pending.cancelEvent() != null
                                    || pending.orderBatch != null && pending.orderBatch.laneCommitEvent != null)
                                    ? owner.laneCommandContexts.required(sequence).matchingResult()
                                    : awaitMatchingResult(owner, sequence);
                    if (matching == null) matching = awaitMatchingResult(owner, sequence);
                    if (matching == null && owner.hasPendingMatchingRejection(sequence)) {
                        response = owner.commits.completeRejectedMatching(sequence);
                    } else if (matching == null) {
                        throw new IllegalStateException(
                                "matcher did not complete sequence " + sequence);
                    } else {
                        response = owner.commits.completeMatching(sequence, matching, clusterTimestamp, clusterPosition);
                    }
                }
                if (response != null && sequence == requestedSequence) {
                    requestedResponse = response;
                    break;
                }
            }
            if (requestedSequence != 0 && requestedResponse == null) {
                throw new IllegalStateException(
                        "synchronous matcher did not produce a terminal response for sequence "
                                + requestedSequence);
            }
            return requestedResponse;
        } finally {
            owner.endDownstreamPublicationBatch();
        }
    }

    static com.surprising.aeron.service.matching.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence) {
        return awaitMatchingResult(state, sequence, TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS);
    }

    static com.surprising.aeron.service.matching.MatchingResult awaitMatchingResult(
            TradingCoreRuntime state, long sequence, long timeoutNanos) {
        if (state.fatalFailure != null) return null;
        if (timeoutNanos <= 0) return null;
        long deadline = System.nanoTime() + timeoutNanos;
        CommandSlot pending = state.pendingMatching.get(sequence);
        while (pending != null && state.placeAdmissionOutstanding(pending)
                && !state.hasPendingMatchingRejection(sequence) && System.nanoTime() < deadline) {
            state.progressPlaceBatchAdmissions();
            if (state.placeAdmissionOutstanding(pending)) Thread.onSpinWait();
        }
        if (state.hasPendingMatchingRejection(sequence)) return null;
        int idle = 0;
        while (state.pendingMatching.contains(sequence) && System.nanoTime() < deadline) {
            CommandSlot head = state.pendingMatching.get(state.pendingMatching.firstSequence());
            if (head != null && head.orderBatch != null && !head.orderBatch.activated())
                state.batches.activateOrderBatch(head.orderBatch, head, true);
            state.drainMatchingCompletions();
            if (state.hasPendingMatchingRejection(sequence)) return null;
            CommandSlot context = state.laneCommandContexts.required(sequence);
            com.surprising.aeron.service.matching.MatchingResult result = context.matchingResult();
            if (result == null) result = context.takeMatchingCompletion();
            if (result == null) {
                var active = state.pendingMatching.get(sequence);
                var event = active.settlementEvent();
                if (event == null && active.orderBatch != null)
                    event = active.orderBatch.itemSettlementEvent != null
                            ? active.orderBatch.itemSettlementEvent : active.orderBatch.settlementEvent;
                if (event != null && event.direct() && event.ready()) result = event.directResult();
            }
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
