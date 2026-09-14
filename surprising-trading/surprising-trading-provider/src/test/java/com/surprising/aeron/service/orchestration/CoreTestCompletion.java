package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.service.matching.CoreMatchingResult;

/** Test-only synchronous adapter for driving the asynchronous Matcher/Lane pipeline. */
final class CoreTestCompletion {
    private CoreTestCompletion() {}

    static CoreResponse completeMatchingSynchronously(TradingCoreRuntime owner,
                                                       long requestedSequence,
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
                owner.progressPlaceAdmissions();
                CoreResponse response;
                if (owner.hasPendingMatchingRejection(sequence)) {
                    response = owner.commits.completeRejectedMatching(sequence);
                } else {
                    PendingMatching pending = owner.pendingMatching.get(sequence);
                    CoreMatchingResult matching = pending != null
                            && pending.orderBatch != null
                            && (pending.orderBatch.itemSettlementEvent != null
                            || pending.orderBatch.itemAdmission != null && pending.orderBatch.activated())
                            ? pending.orderBatch.lastMatchingResult
                            : pending != null
                            && (pending.settlementEvent() != null || pending.cancelEvent() != null
                            || pending.replaceEvent() != null
                            || pending.orderBatch != null && pending.orderBatch.laneCommitEvent != null)
                            ? owner.laneCommandContexts.required(sequence).matchingResult()
                            : awaitMatchingResult(owner, sequence);
                    if (matching == null && owner.hasPendingMatchingRejection(sequence)) {
                        response = owner.commits.completeRejectedMatching(sequence);
                    } else if (matching == null) {
                        throw new IllegalStateException("matcher did not complete sequence " + sequence);
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
                throw new IllegalStateException("synchronous matcher did not produce a terminal response for sequence " + requestedSequence);
            }
            return requestedResponse;
        } finally {
            owner.endDownstreamPublicationBatch();
        }
    }

    private static CoreMatchingResult awaitMatchingResult(TradingCoreRuntime owner, long sequence) {
        long deadline = System.nanoTime() + TradingCoreRuntime.MATCHING_AWAIT_TIMEOUT_NANOS;
        PendingMatching pending = owner.pendingMatching.get(sequence);
        while (pending != null && owner.placeAdmissionOutstanding(pending)
                && !owner.hasPendingMatchingRejection(sequence) && System.nanoTime() < deadline) {
            owner.progressPlaceAdmissions();
            if (owner.placeAdmissionOutstanding(pending)) Thread.onSpinWait();
        }
        if (owner.hasPendingMatchingRejection(sequence)) return null;
        while (owner.pendingMatching.contains(sequence) && System.nanoTime() < deadline) {
            PendingMatching head = owner.pendingMatching.get(owner.pendingMatching.firstSequence());
            if (head != null && head.orderBatch != null && !head.orderBatch.activated()) {
                owner.batches.activateOrderBatch(head.orderBatch, head, true);
            }
            owner.drainMatchingCompletions();
            if (owner.hasPendingMatchingRejection(sequence)) return null;
            LaneCommandContextRing.Context context = owner.laneCommandContexts.required(sequence);
            CoreMatchingResult result = context.matchingResult();
            if (result == null) result = context.takeMatchingCompletion();
            if (result != null) return result;
            Thread.onSpinWait();
        }
        return null;
    }
}
