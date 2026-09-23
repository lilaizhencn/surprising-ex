package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.command.order.OrderBatchKind;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.PlaceBatchAdmissionEvent;
import com.surprising.aeron.service.state.TradingRuntimeState;

/**
 * Owns Owner-thread progress between Account Lane admission, Matcher completion and ordered
 * commit. It does not own orders, balances or command slots; those remain owned by the runtime,
 * {@link TradingRuntimeState} and {@link PendingMatchingRing} respectively.
 */
final class MatchingPipelineProgress {
    private final TradingCoreRuntime owner;
    /** Wake-up hints for matcher shards whose submission head can advance. */
    private long readyShardMask;
    /** Bounded health-check cadence for the empty notification path. */
    private long nextHealthCheckNs = System.nanoTime();

    MatchingPipelineProgress(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    void submissionHeadReleased(int shard) {
        if (shard >= 0) readyShardMask |= 1L << shard;
    }

    boolean submissionDeferred(long sequence) {
        CommandSlot pending = owner.pendingMatching.get(sequence);
        if (pending != null) {
            int shardId = owner.matchingFlow.pendingSubmissionShard(pending);
            if (!owner.pendingMatching.isSubmissionHead(sequence, shardId)) return true;
        }
        if (pending != null && pending.clusterIndependent || !owner.batches.hasPendingBatches()) return false;
        return sequence > owner.batches.firstBatch().sequence;
    }

    void submissionCompleted(CommandSlot pending) {
        owner.pendingMatching.progressChanged();
        int shard = owner.matchingFlow.pendingSubmissionShard(pending);
        owner.pendingMatching.completeSubmission(pending.sequence());
        // Wake the next command on this shard as soon as the submission head is released. A
        // normal PLACE may have been followed by a cancel/control command whose submission was
        // deferred while the PLACE admission was in flight; without this bit the next command
        // would remain parked until another Lane notification arrived.
        if (pending.orderBatch != null || owner.pendingMatching.submissionHead(shard) != null
                || owner.pendingMatching.hasDeferred()) {
            readyShardMask |= 1L << shard;
        }
    }

    /**
     * Advances only pipelined batch admissions. A normal PLACE is submitted to the Matcher as
     * soon as its immutable admission input is prepared; its Lane completion is resolved by the
     * ordered commit head and never drives an Owner-side submission poll.
     */
    void progressPlaceBatchAdmissions() {
        drainPlaceAdmissionNotifications();
        if (readyShardMask == 0 && hasDeferredMatchingSubmission()) {
            CommandSlot head = owner.pendingMatching.get(owner.pendingMatching.firstSequence());
            readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(head);
        }
        long readyShards = readyShardMask;
        while (readyShards != 0) {
            int shard = Long.numberOfTrailingZeros(readyShards);
            long shardBit = 1L << shard;
            readyShards &= ~shardBit;
            while ((readyShardMask & shardBit) != 0) {
                CommandSlot pending = owner.pendingMatching.submissionHead(shard);
                if (pending == null) {
                    readyShardMask &= ~shardBit;
                    break;
                }
                var admission = pending.placeAdmission();
                OrderBatchPending orderBatch = pending.orderBatch;
                PlaceBatchAdmissionEvent batchAdmission = orderBatch == null
                        ? null : orderBatch.placeBatchAdmissionEvent;
                if (ownerHasPendingMatchingRejection(pending)) {
                    // A synchronous Lane admission may have rejected the PLACE before a
                    // submission head was registered. Its ordered rejection is consumed by the
                    // commit coordinator; never try to rebuild a Matcher order for it.
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (admission == null && batchAdmission == null) {
                    if (orderBatch != null && pending.clusterIndependent
                            && orderBatch.kind == OrderBatchKind.CANCEL && orderBatch.activated()) {
                        owner.matchingFlow.submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    // A control or matching command may have been held behind an earlier
                    // submission head. Sequential order batches own their item cursor and are
                    // resumed by OrderBatchExecutor; submitting them here would route the same
                    // matcher token twice.
                    if (orderBatch == null && !pending.isMatchingSubmitted() && !pending.deferredMatching()) {
                        owner.matchingFlow.submitMatching(pending);
                        if (pending.isMatchingSubmitted()) continue;
                    }
                    readyShardMask &= ~shardBit;
                    break;
                }
                if (batchAdmission != null) {
                    if (!batchAdmission.complete()) {
                        // The shard bit is only a wake-up hint. Keep it until the submission
                        // head's event is complete; a later batch on the same shard may have
                        // already coalesced into this bit.
                        break;
                    }
                    owner.identities.recordLaneClientAllocations(batchAdmission.takeIdentityAllocations());
                    RuntimeException rejection = batchAdmission.rejection();
                    if (rejection != null) {
                        owner.runtimeState.discardPlaceBatchAdmission(batchAdmission);
                        owner.runtimeState.releasePlaceBatchAdmission(batchAdmission);
                        orderBatch.placeBatchAdmissionEvent = null;
                        owner.batches.unregisterPipelinedBatchSymbols(orderBatch);
                        orderBatch.rollbackPreparedClientKeys(owner.identities);
                        orderBatch.pipelined = false;
                        orderBatch.sequentialAdmission = true;
                        // The Lane already checked and rolled back the only item. There is no
                        // partial batch to re-evaluate, so retain its rejection at ordered commit.
                        if (orderBatch.items.size() == 1
                                && (rejection instanceof CoreStateRejectedException
                                || rejection instanceof ArithmeticException
                                || rejection instanceof IllegalArgumentException)) {
                            CoreResultCode code = rejection instanceof CoreStateRejectedException stateRejection
                                    ? CoreResultCode.fromRejectionCode(stateRejection.code())
                                    : rejection instanceof ArithmeticException
                                    ? CoreResultCode.ARITHMETIC_OVERFLOW : CoreResultCode.INVALID_COMMAND;
                            owner.batches.appendOrderBatchResult(orderBatch, orderBatch.items.getFirst(),
                                    ResponseStatus.REJECTED, code);
                            orderBatch.nextIndex = 1;
                        }
                        if (orderBatch.admissionOrderIndex != null)
                            orderBatch.admissionOrderIndex.reset(pending.command().header().userId());
                        readyShardMask &= ~shardBit;
                        if (orderBatch.commitStarted()) {
                            owner.restoreMatchingCommitContext(pending);
                            try {
                                owner.batches.startOrderBatchItem(orderBatch, pending,
                                        orderBatch.clusterTimestamp, orderBatch.clusterPosition, true);
                            } finally {
                                owner.clearFactContext();
                            }
                        } else {
                            orderBatch.activated(false);
                            submitDeferredMatchingAfterBatch();
                        }
                        break;
                    }
                    if (!orderBatch.admissionCollected()) {
                        owner.runtimeState.stagePlaceBatchAdmission(batchAdmission);
                        orderBatch.admissionCollected(true);
                    }
                    if (!pending.isMatchingSubmitted()) {
                        owner.batches.submitPipelinedPlaceBatch(pending, orderBatch);
                        pending.matchingSubmitted();
                        submissionCompleted(pending);
                    }
                    continue;
                }
                // A normal PLACE that was held behind an earlier submission can now enter the
                // Matcher even while its Account Lane admission is still running. This is a
                // submission-head transition, not an admission poll.
                if (!pending.isMatchingSubmitted()) {
                    owner.matchingFlow.submitMatching(pending);
                    if (pending.isMatchingSubmitted()) continue;
                }
                readyShardMask &= ~shardBit;
                break;
            }
        }
    }

    /** Drain completion cursors only to wake the batch dispatcher or ordered commit head. */
    void drainPlaceAdmissionNotifications() {
        readyShardMask |= owner.runtimeState.takePlaceBatchAdmissionReadyShardMask();
    }

    void submitDeferredMatchingAfterBatch() {
        while (!owner.pendingMatching.isEmpty()) {
            // Both orderings append increasing Core sequences. Only their heads can advance.
            OrderBatchPending batch = owner.batches.firstBatch();
            CommandSlot deferredPending = owner.pendingMatching.firstDeferred();
            Long deferredSequence = deferredPending == null ? null : deferredPending.sequence();
            CommandSlot pending;
            if (deferredSequence != null && (batch == null || deferredSequence < batch.sequence)) {
                pending = owner.pendingMatching.get(deferredSequence);
                batch = null;
            } else if (batch != null && !batch.activated()) {
                pending = owner.pendingMatching.get(batch.sequence);
            } else {
                return;
            }
            if (batch != null && !batch.activated()) {
                if (owner.batches.tryActivatePipelinedOrderBatch(batch, pending)) continue;
                owner.batches.activateOrderBatch(batch, pending, true);
                return;
            }
            if (pending != null && pending.deferredMatching()) {
                CoreResponse response = owner.admissions.prepareMatching(pending.command(), pending.deferredClusterTimestamp(),
                        pending.deferredClusterPosition(), pending.deferredSourceKey(), pending.operation(),
                        pending.fingerprint(), pending);
                if (response == null) return;
                if (response.status() == ResponseStatus.REJECTED) continue;
                continue;
            }
        }
    }

    boolean hasDrainWork() {
        return owner.crossShardCancellations.hasPending()
                || readyShardMask != 0
                || owner.runtimeState.hasMatchingNotifications()
                || owner.matcherPipeline.hasMatchingCompletions()
                || hasLocalMatchingWork();
    }

    void drainMatchingCompletions() {
        if (owner.crossShardCancellations.hasPending()) owner.crossShardCancellations.poll();
        drainPlaceAdmissionCompletions();
        if (readyShardMask != 0 || owner.runtimeState.hasPlaceAdmissionNotifications()
                || hasDeferredMatchingSubmission()) {
            progressPlaceBatchAdmissions();
        }
        if (owner.matcherPipeline.hasMatchingCompletions()) {
            owner.matcherPipeline.drainMatchingCompletions();
            owner.pendingMatching.progressChanged();
        }
        if (owner.runtimeState.hasSettlementNotifications()) owner.commits.drainMatcherSettlementCompletions();
    }

    /** Drain only Owner scheduling cursors; the admission payload remains in its command event. */
    private void drainPlaceAdmissionCompletions() {
        long readyLanes = owner.runtimeState.takePlaceAdmissionReadyLaneMask();
        while (readyLanes != 0) {
            int laneId = Long.numberOfTrailingZeros(readyLanes);
            readyLanes &= readyLanes - 1;
            long sequence;
            while ((sequence = owner.runtimeState.pollPlaceAdmissionReady(laneId)) != 0) {
                CommandSlot pending = owner.pendingMatching.get(sequence);
                if (pending == null || pending.placeAdmission() == null) continue;
                if (!pending.isMatchingSubmitted()) {
                    readyShardMask |= 1L << owner.matchingFlow.pendingSubmissionShard(pending);
                } else if (owner.matchingFlow.collectPlaceAdmissionIfReady(pending)) {
                    owner.pendingMatching.progressChanged();
                }
            }
        }
    }

    /** Owner-local progress must continue even when no new cross-thread notification arrives. */
    boolean hasLocalMatchingWork() {
        if (readyShardMask != 0) return true;
        CommandSlot head = owner.pendingMatching.get(owner.pendingMatching.firstSequence());
        OrderBatchPending batch = head == null ? null : head.orderBatch;
        // Handoff and the final metadata commit have no matcher-settlement cursor. Keep polling
        // these bounded Owner continuations even after consuming the last notification.
        // The throughput profile runs Owner on an exclusive CPU and intentionally keeps polling
        // while a Lane commit is pending.  Completion correctness is still decided by the event
        // bits and TradingCoreRuntime.hasCompletedLaneCommit(); this condition only selects the
        // production busy-spin scheduling policy.
        return batch != null && (!batch.activated() || batch.laneCommitEvent != null || batch.itemAdmission != null)
                || head != null && !head.isMatchingSubmitted() && head.placeAdmission() == null
                && !head.deferredMatching()
                || head != null && owner.commits.matchingCommitReady(head);
    }

    private boolean hasDeferredMatchingSubmission() {
        CommandSlot head = owner.pendingMatching.get(owner.pendingMatching.firstSequence());
        return head != null && !ownerHasPendingMatchingRejection(head)
                && !head.isMatchingSubmitted() && !head.deferredMatching()
                && head.orderBatch == null;
    }

    private boolean ownerHasPendingMatchingRejection(CommandSlot pending) {
        return pending != null && owner.laneCommandContexts.claimed(pending.sequence())
                && owner.laneCommandContexts.required(pending.sequence()).hasMatchingRejection();
    }

    boolean hasNotifications() {
        return hasNotifications(System.nanoTime());
    }

    boolean hasNotifications(long now) {
        // A failed Lane need not publish a completion. Keep a bounded empty-path health check;
        // apply/commit still check on every invocation, including ready notifications.
        if (now - nextHealthCheckNs >= 0) {
            owner.assertHealthy();
            nextHealthCheckNs = now + 1_000_000L;
        }
        return owner.runtimeState.hasMatchingNotifications() || owner.matcherPipeline.hasMatchingCompletions();
    }
}
