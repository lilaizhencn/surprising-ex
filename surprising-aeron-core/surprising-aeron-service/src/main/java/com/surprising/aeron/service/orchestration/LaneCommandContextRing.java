package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;

public final class LaneCommandContextRing {
    private final Context[] contexts;
    private final int mask;
    private int inFlight;
    private int highWaterMark;

    public LaneCommandContextRing(int capacity, int laneCount) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0
                || laneCount <= 0 || laneCount > Long.SIZE) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new Context[capacity];
        for (int index = 0; index < capacity; index++) contexts[index] = new Context(laneCount);
        mask = capacity - 1;
    }

    Context claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        Context context = contexts[(int) coreSequence & mask];
        if (context.coreSequence != 0) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    public Context required(long coreSequence) {
        int slot = (int) coreSequence & mask;
        Context context = contexts[slot];
        if (context.coreSequence != coreSequence) {
            throw new IllegalStateException("unknown lane command context sequence=" + coreSequence
                    + " slot=" + slot + " actual=" + context.coreSequence
                    + " inFlight=" + inFlight + " capacity=" + contexts.length);
        }
        return context;
    }

    Context contextAt(int slot) { return contexts[slot]; }

    void release(long coreSequence) {
        Context context = required(coreSequence);
        if (!context.complete() || context.submittedMatcherShard != -1)
            throw new IllegalStateException("incomplete lane command context");
        context.clear();
        inFlight--;
    }

    public boolean claimed(long coreSequence) {
        return contexts[(int) coreSequence & mask].coreSequence == coreSequence;
    }

    void discard(long coreSequence) {
        Context context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    int inFlight() { return inFlight; }
    int highWaterMark() { return highWaterMark; }
    int capacity() { return contexts.length; }

    /**
     * The sequence slot is also the pending-command carrier.  Keeping the lane barrier and the
     * pending command in one object removes the old two-object lifecycle (Context -> PendingMatching)
     * and makes slot ownership explicit: one sequence, one reusable slot.
     */
    public static final class Context extends PendingMatching
            implements com.surprising.aeron.service.state.MatcherSettlementEvent.MatcherCompletionRoute {
        private long coreSequence;
        private long expectedLaneMask;
        private long completedLaneMask;
        private CoreMatchingResult completedMatchingResult;
        private CoreMatchingResult matchingResult;
        /** Owner-only route for the current matcher submission, owned by this existing sequence slot. */
        private int submittedMatcherShard = -1;

        public int submittedMatcherShard() { return submittedMatcherShard; }

        public void claimMatcherSubmission(int shardId) {
            if (coreSequence == 0 || shardId < 0 || submittedMatcherShard != -1)
                throw new IllegalStateException("matcher command token is already routed or inactive");
            submittedMatcherShard = shardId;
        }

        @Override
        public void releaseMatcherSubmission(int shardId) {
            if (submittedMatcherShard != shardId)
                throw new IllegalStateException("matcher completion belongs to another shard");
            submittedMatcherShard = -1;
        }

        /**
         * Suspended commit inputs are transferred between the owner builder and this sequence
         * slot.  The slot never materializes a boxed List; the owner swaps its reusable primitive
         * buffers in and out when a continuation yields.
         */
        private PrimitiveLongChangeSet commitChangedUserIds = new PrimitiveLongChangeSet();
        private PrimitiveLongChangeSet commitChangedOrderIds = new PrimitiveLongChangeSet();
        private boolean commitContextActive;
        private final com.surprising.aeron.service.state.RuntimeFundsAccumulator commitFundsAccumulator =
                new com.surprising.aeron.service.state.RuntimeFundsAccumulator(32);
        private boolean commitSnapshotDirty;
        private boolean commitSnapshotProvisionalOnly;
        private CoreResultCode matchingRejection;
        /** 结算计划由序号槽独占；终态结果消费后清理引用并保留容量。 */
        private com.surprising.aeron.service.state.MatcherSettlementPlan reusableSettlementPlan;
        /** Compatibility carrier for recovery/tests that supply an externally built pending object. */
        private PendingMatching pending;
        com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlanBuffer() {
            if (coreSequence == 0) throw new IllegalStateException("unclaimed settlement slot");
            if (reusableSettlementPlan == null)
                reusableSettlementPlan = new com.surprising.aeron.service.state.MatcherSettlementPlan();
            return reusableSettlementPlan;
        }
        private Context(int laneCount) {
            if (laneCount <= 0 || laneCount > Long.SIZE) {
                throw new IllegalArgumentException("invalid account lane count");
            }
        }

        @Override
        PendingMatching initialize(long sequence, Operation operation, com.surprising.aeron.protocol.CoreMessage command,
                                   com.surprising.aeron.protocol.CommandFingerprint fingerprint,
                                   java.util.List<Long> preMatchingCancellationOrderIds,
                                   com.surprising.aeron.service.state.RuntimeProjectionPoint beforeProjection,
                                   long beforeBusinessStateHash, long beforeFundsStateHash,
                                   com.surprising.aeron.service.state.RuntimeFundsDelta fundsDelta,
                                   DecodedMatchingCommand decodedCommand,
                                   ResolvedMatchingAdmission admission) {
            super.initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                    beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                    decodedCommand, admission);
            clearLifecycleState();
            coreSequence = sequence;
            pending = this;
            return this;
        }

        long coreSequence() { return coreSequence; }
        PendingMatching pending() { return pending; }
        void pending(PendingMatching value) {
            if (value == null || value.sequence() != coreSequence) {
                throw new IllegalStateException("invalid pending matching sequence");
            }
            pending = value;
        }
        long expectedLaneMask() { return expectedLaneMask; }
        long completedLaneMask() { return completedLaneMask; }
        CoreMatchingResult matchingResult() { return matchingResult; }
        void publishMatchingCompletion(CoreMatchingResult result) {
            if (result == null || result.nativeCommand().coreSequence() != coreSequence) {
                throw new IllegalStateException("invalid matching completion");
            }
            if (completedMatchingResult == null) completedMatchingResult = result;
        }

        CoreMatchingResult takeMatchingCompletion() {
            CoreMatchingResult result = completedMatchingResult;
            completedMatchingResult = null;
            return result;
        }

        CoreMatchingResult matchingCompletion() { return completedMatchingResult; }
        boolean hasMatchingCompletion() { return completedMatchingResult != null; }
        void suspendCommitContext(CommandResultBuilder resultBuilder,
                                  com.surprising.aeron.service.state.RuntimeFundsAccumulator fundsAccumulator,
                                  boolean snapshotDirty, boolean snapshotProvisionalOnly) {
            if (resultBuilder == null || fundsAccumulator == null || commitContextActive) {
                throw new IllegalStateException("invalid suspended sequence commit context");
            }
            PrimitiveLongChangeSet reusableUserIds = commitChangedUserIds;
            PrimitiveLongChangeSet reusableOrderIds = commitChangedOrderIds;
            commitChangedUserIds = resultBuilder.changedUserIds;
            commitChangedOrderIds = resultBuilder.changedOrderIds;
            resultBuilder.changedUserIds = reusableUserIds;
            resultBuilder.changedOrderIds = reusableOrderIds;
            commitFundsAccumulator.clear();
            fundsAccumulator.transferToEmpty(commitFundsAccumulator);
            commitSnapshotDirty = snapshotDirty;
            commitSnapshotProvisionalOnly = snapshotProvisionalOnly;
            commitContextActive = true;
        }

        /** Restore the suspended primitive buffers and return the owner-owned buffers to the slot. */
        void restoreCommitContext(CommandResultBuilder resultBuilder) {
            requireCommit();
            if (resultBuilder == null) throw new IllegalStateException("result builder is required");
            PrimitiveLongChangeSet committedUserIds = commitChangedUserIds;
            PrimitiveLongChangeSet committedOrderIds = commitChangedOrderIds;
            commitChangedUserIds = resultBuilder.changedUserIds;
            commitChangedOrderIds = resultBuilder.changedOrderIds;
            resultBuilder.changedUserIds = committedUserIds;
            resultBuilder.changedOrderIds = committedOrderIds;
        }

        PrimitiveLongChangeSet commitChangedUserIds() { return requiredCommit(commitChangedUserIds); }
        PrimitiveLongChangeSet commitChangedOrderIds() { return requiredCommit(commitChangedOrderIds); }
        /** 恢复本序号时交还原资金缓冲，不把已汇总的逐账户 posting 再合并一次。 */
        void takeCommitFundsTo(com.surprising.aeron.service.state.RuntimeFundsAccumulator target) {
            requireCommit();
            target.clear();
            commitFundsAccumulator.transferToEmpty(target);
        }
        boolean commitSnapshotDirty() { requireCommit(); return commitSnapshotDirty; }
        boolean commitSnapshotProvisionalOnly() { requireCommit(); return commitSnapshotProvisionalOnly; }

        void clearCommitContext() {
            requireCommit();
            commitChangedUserIds.clear();
            commitChangedOrderIds.clear();
            commitFundsAccumulator.clear();
            commitSnapshotDirty = false;
            commitSnapshotProvisionalOnly = false;
            commitContextActive = false;
        }

        private void requireCommit() {
            if (!commitContextActive) throw new IllegalStateException("sequence commit context is missing");
        }

        private <T> T requiredCommit(T value) {
            requireCommit();
            return value;
        }

        boolean hasCommitContext() { return commitContextActive; }

        void rejectMatching(CoreResultCode resultCode) {
            if (resultCode == null || matchingRejection != null) {
                throw new IllegalStateException("invalid matching rejection");
            }
            matchingRejection = resultCode;
        }

        boolean hasMatchingRejection() { return matchingRejection != null; }


        CoreResultCode matchingRejection() {
            if (matchingRejection == null) throw new IllegalStateException("matching rejection is missing");
            return matchingRejection;
        }

        void result(CoreMatchingResult result, long expectedMask, long validLaneMask) {
            if (result == null || result.nativeCommand().coreSequence() != coreSequence
                    || (expectedMask & ~validLaneMask) != 0
                    || matchingResult != null) {
                throw new IllegalStateException("invalid immutable matching result fanout");
            }
            matchingResult = result;
            expectedLaneMask = expectedMask;
        }

        void resetMatchingContinuation() { completedMatchingResult = null; }

        void includeControlLanes(long laneMask, long validLaneMask) {
            if (matchingResult == null || completedLaneMask != 0 || (laneMask & ~validLaneMask) != 0) {
                throw new IllegalStateException("invalid synchronous control lane participants");
            }
            expectedLaneMask |= laneMask;
        }

        void completeLanes(long laneMask) {
            if (matchingResult == null
                    || (laneMask & ~expectedLaneMask) != 0
                    || (completedLaneMask & laneMask) != 0) {
                throw new IllegalStateException("duplicate or unexpected account lane completion"
                        + " laneMask=" + laneMask + " expected=" + expectedLaneMask
                        + " completed=" + completedLaneMask);
            }
            completedLaneMask |= laneMask;
        }

        boolean complete() {
            return matchingResult != null && completedLaneMask == expectedLaneMask;
        }

        private void clear() {
            if (reusableSettlementPlan != null) reusableSettlementPlan.clearReferences();
            // PendingMatching is overwritten by initialize() before the slot can be observed
            // again.  Clearing every reference here duplicates that work on the owner hot path
            // and only reduces retention during the short free interval.
            pending = null;
            clearLifecycleState();
        }

        private void clearLifecycleState() {
            coreSequence = 0;
            expectedLaneMask = 0;
            completedLaneMask = 0;
            completedMatchingResult = null;
            matchingResult = null;
            submittedMatcherShard = -1;
            commitChangedUserIds.clear();
            commitChangedOrderIds.clear();
            commitFundsAccumulator.clear();
            commitSnapshotDirty = false;
            commitSnapshotProvisionalOnly = false;
            commitContextActive = false;
            matchingRejection = null;
        }
    }
}
