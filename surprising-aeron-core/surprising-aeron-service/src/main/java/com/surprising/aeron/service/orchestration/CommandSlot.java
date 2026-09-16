package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.LaneCancelEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.List;
import java.util.Objects;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.service.matching.CoreMatchingResult;

/**
 * Reusable command lifetime: input, business progress, Lane completion and final result.
 * Owner writes orchestration fields; matcher publication follows MatcherCompletionRoute.
 * The ring's claimed bit controls reuse, while sequence remains readable until the next claim.
 */
public final class CommandSlot implements com.surprising.aeron.service.state.MatcherSettlementEvent.MatcherCompletionRoute {
    /** Optional batch context owned by this pending command, detached before command-slot reuse. */
    OrderBatchPending orderBatch;
    private Operation operation;
    private CoreMessage command;
    private CommandFingerprint fingerprint;
    private List<Long> preMatchingCancellationOrderIds;
    private RuntimeProjectionPoint beforeProjection;
    private long beforeBusinessStateHash;
    private long beforeFundsStateHash;
    private RuntimeFundsDelta fundsDelta;
    private DecodedMatchingCommand decodedCommand;
    private ResolvedMatchingAdmission admission;
    private long pendingStateHash;
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;
    private boolean commitFenceEstablished;
    /** Exactly one settlement/cancel continuation can own a command at a time. */
    private ContinuationKind continuationKind;
    private Object continuation;
    /** Normal PLACE admission runs alongside the settlement continuation. */
    private PlaceAdmissionEvent placeAdmission;
    private com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan;
    private long settlementApplyStartNanos;
    /** 普通 PLACE 已在 Owner 解析，Lane 完成后直接复用该值，避免撮合命令副本。 */
    private ResolvedPlaceOrder admittedPlaceOrder;
    private CoreMatchingOrder admittedMatchingOrder;
    private boolean matchingSubmitted;
    /** 跨分片清算撤单已进入异步协调队列，防止重复派发。 */
    boolean crossShardCancellationStarted;
    boolean clusterIndependent;
    /** 准入时固定的潜在成交账户分区，整个命令终态前保持不变。 */
    long partitionLaneMask;
    /** 派发前的不可变订单身份；仅实时推送启用时保留，提交后释放。 */
    com.surprising.aeron.service.state.OrderRuntime realtimeTakerOrder;
    private boolean dispatchOnly;
    private boolean pipelinedSettlementCounted;
    /** Fast lifecycle bit for deferred commands; avoids a boxed LinkedHashMap probe in Owner polling. */
    private boolean deferredMatching;
    /** Cached matcher shard for a non-batch command; stable until this slot is recycled. */
    private int cachedMatcherShard = -1;

    // Only the reusable control-command slot uses these fields. No per-command continuation wrapper.
    boolean directActive;
    TradingCoreRuntime.SourceKey sourceKey;
    long beforeRevision, checkpoint, identityCheckpoint;
    java.util.function.BooleanSupplier controlWork;
    com.surprising.aeron.protocol.ResponseStatus status;
    CoreResultCode resultCode;
    com.surprising.aeron.service.state.LaneCommitEvent commitEvent;
    boolean finalizationPrepared;

    boolean controlPending;
    long controlStartedNanos;
    byte[] resultData;

    void deferControl(java.util.function.BooleanSupplier work) {
        if (controlWork != null) throw new IllegalStateException("command already has pending work");
        controlWork = Objects.requireNonNull(work);
    }

    void initializeDirect(CoreMessage command, CommandFingerprint fingerprint,
            TradingCoreRuntime.SourceKey sourceKey, long timestamp, long position,
            RuntimeProjectionPoint beforeProjection, long beforeRevision,
            long checkpoint, long identityCheckpoint) {
        if (directActive) throw new IllegalStateException("control command slot is occupied");
        this.command = command;
        this.fingerprint = fingerprint;
        this.sourceKey = sourceKey;
        this.beforeProjection = beforeProjection;
        this.beforeRevision = beforeRevision;
        this.checkpoint = checkpoint;
        this.identityCheckpoint = identityCheckpoint;
        commitFenceTimestamp = timestamp;
        commitFenceClusterPosition = position;
        commitFenceEstablished = true;
        directActive = true;
    }

    void clearDirect() {
        directActive = false;
        command = null;
        fingerprint = null;
        sourceKey = null;
        beforeProjection = null;
        controlWork = null;
        status = null;
        resultCode = null;
        commitEvent = null;
        finalizationPrepared = false;
        commitFenceEstablished = false;
    }

    CommandSlot() {
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of(), beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, List.of(), beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, null);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, null);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand,
                admission);
    }

    CommandSlot(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                decodedCommand, admission);
    }

    CommandSlot initialize(long sequence, Operation operation, CoreMessage command,
                               CommandFingerprint fingerprint, List<Long> preMatchingCancellationOrderIds,
                               RuntimeProjectionPoint beforeProjection, long beforeBusinessStateHash,
                               long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                               DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        if (sequence <= 0 || operation == null || command == null || preMatchingCancellationOrderIds == null
                || fingerprint == null || beforeProjection == null || fundsDelta == null || decodedCommand == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        clearLifecycleState();
        this.coreSequence = sequence;
        claimed = true;
        orderBatch = null;
        this.operation = operation;
        this.command = command;
        this.fingerprint = fingerprint;
        this.preMatchingCancellationOrderIds = retainCancellationIds(preMatchingCancellationOrderIds);
        this.beforeProjection = beforeProjection;
        this.beforeBusinessStateHash = beforeBusinessStateHash;
        this.beforeFundsStateHash = beforeFundsStateHash;
        this.fundsDelta = fundsDelta;
        this.decodedCommand = decodedCommand;
        this.admission = admission;
        pendingStateHash = 0;
        commitFenceTimestamp = 0;
        commitFenceClusterPosition = 0;
        commitFenceEstablished = false;
        continuationKind = null;
        continuation = null;
        placeAdmission = null;
        settlementPlan = null;
        settlementApplyStartNanos = 0;
        admittedMatchingOrder = null;
        admittedPlaceOrder = null;
        matchingSubmitted = false;
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        partitionLaneMask = 0;
        realtimeTakerOrder = null;
        dispatchOnly = false;
        pipelinedSettlementCounted = false;
        deferredMatching = false;
        cachedMatcherShard = -1;
        return this;
    }

    CommandSlot withPreMatchingCancellations(List<Long> orderIds) {
        preMatchingCancellationOrderIds = retainCancellationIds(orderIds);
        return this;
    }

    /** Internal admission paths already hand over immutable primitive storage. Keep it instead of
     * copying into a boxed Object[] for every derivative PLACE/REPLACE command. */
    private static List<Long> retainCancellationIds(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        if (orderIds instanceof ImmutableLongArrayList) {
            return orderIds;
        }
        return List.copyOf(orderIds);
    }

    CommandSlot withAdmission(ResolvedMatchingAdmission nextAdmission) {
        admission = nextAdmission;
        return this;
    }


    CommandSlot withPendingStateHash(long stateHash) {
        pendingStateHash = stateHash;
        return this;
    }

    void establishCommitFence(long clusterTimestamp, long clusterPosition) {
        if (clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalArgumentException("matching commit fence cannot be negative");
        }
        if (commitFenceEstablished) return;
        commitFenceTimestamp = clusterTimestamp;
        commitFenceClusterPosition = clusterPosition;
        commitFenceEstablished = true;
    }

    long sequence() { return coreSequence; }
    Operation operation() { return operation; }
    CoreMessage command() { return command; }
    CommandFingerprint fingerprint() { return fingerprint; }
    List<Long> preMatchingCancellationOrderIds() { return preMatchingCancellationOrderIds; }
    RuntimeProjectionPoint beforeProjection() { return beforeProjection; }
    long beforeBusinessStateHash() { return beforeBusinessStateHash; }
    long beforeFundsStateHash() { return beforeFundsStateHash; }
    RuntimeFundsDelta fundsDelta() { return fundsDelta; }
    DecodedMatchingCommand decodedCommand() { return decodedCommand; }
    ResolvedMatchingAdmission matchingAdmission() { return admission; }
    long pendingStateHash() { return pendingStateHash; }
    com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent() {
        return continuationKind == ContinuationKind.SETTLEMENT
                ? (com.surprising.aeron.service.state.MatcherSettlementEvent) continuation : null;
    }
    com.surprising.aeron.service.state.MatcherSettlementEvent takeSettlementEvent() {
        var value = settlementEvent();
        clearContinuation(ContinuationKind.SETTLEMENT);
        return value;
    }
    LaneCancelEvent cancelEvent() {
        return continuationKind == ContinuationKind.CANCEL ? (LaneCancelEvent) continuation : null;
    }
    LaneCancelEvent takeCancelEvent() {
        LaneCancelEvent value = cancelEvent();
        clearContinuation(ContinuationKind.CANCEL);
        return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    long settlementApplyStartNanos() { return settlementApplyStartNanos; }
    PlaceAdmissionEvent placeAdmission() {
        return placeAdmission;
    }
    boolean hasLaneContinuation() {
        return continuationKind == ContinuationKind.SETTLEMENT
                || continuationKind == ContinuationKind.CANCEL;
    }
    boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return switch (continuationKind) {
            case SETTLEMENT -> settlementEvent().complete();
            case CANCEL -> cancelEvent().complete();
            default -> false;
        };
    }
    PlaceAdmissionEvent takePlaceAdmission() {
        PlaceAdmissionEvent value = placeAdmission;
        placeAdmission = null;
        return value;
    }
    void placeAdmission(PlaceAdmissionEvent event) {
        if (event == null || placeAdmission != null || operation != Operation.PLACE) {
            throw new IllegalStateException("invalid place admission continuation");
        }
        placeAdmission = event;
    }
    void admissionCompleted(ResolvedPlaceOrder resolvedOrder) {
        if (resolvedOrder == null || placeAdmission() == null || admittedPlaceOrder != null
                || admittedMatchingOrder != null) {
            throw new IllegalStateException("invalid completed place admission");
        }
        admittedPlaceOrder = resolvedOrder;
    }
    ResolvedPlaceOrder admittedPlaceOrder() { return admittedPlaceOrder; }
    CoreMatchingOrder admittedMatchingOrder() { return admittedMatchingOrder; }
    /** 沿用已完成冻结的不可变订单，触发续步不得再回读 Lane 的客户端订单索引。 */
    void triggerAdmission(CoreMatchingOrder order) {
        if (operation != Operation.TRIGGER || order == null || admittedMatchingOrder != null)
            throw new IllegalStateException("invalid trigger admission");
        admittedMatchingOrder = order;
    }
    boolean isMatchingSubmitted() { return matchingSubmitted; }
    void matchingSubmitted() {
        if (matchingSubmitted) throw new IllegalStateException("matching command was submitted twice");
        matchingSubmitted = true;
    }
    void cancel(LaneCancelEvent event, long applyStartNanos) {
        if (event == null || continuation != null
                || operation != Operation.CANCEL) {
            throw new IllegalStateException("invalid cancel continuation");
        }
        continuationKind = ContinuationKind.CANCEL;
        continuation = event;
        settlementApplyStartNanos = applyStartNanos;
    }
    void settlement(com.surprising.aeron.service.state.MatcherSettlementEvent event,
                    com.surprising.aeron.service.state.MatcherSettlementPlan plan,
                    long applyStartNanos) {
        if (event == null || plan == null || continuation != null) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        continuationKind = ContinuationKind.SETTLEMENT;
        continuation = event;
        settlementPlan = plan;
        settlementApplyStartNanos = applyStartNanos;
    }

    private void clearContinuation(ContinuationKind expected) {
        if (expected == ContinuationKind.PLACE_ADMISSION) {
            placeAdmission = null;
            return;
        }
        if (continuationKind != expected) return;
        continuationKind = null;
        continuation = null;
    }

    /** Clears the command-owned references when its fixed sequence slot is recycled. */
    void clearReferences() {
        coreSequence = 0;
        orderBatch = null;
        operation = null;
        command = null;
        fingerprint = null;
        preMatchingCancellationOrderIds = List.of();
        beforeProjection = null;
        fundsDelta = null;
        decodedCommand = null;
        admission = null;
        continuationKind = null;
        continuation = null;
        placeAdmission = null;
        settlementPlan = null;
        admittedPlaceOrder = null;
        admittedMatchingOrder = null;
        realtimeTakerOrder = null;
        deferredMatching = false;
        cachedMatcherShard = -1;
    }
    void dispatchOnly() { dispatchOnly = true; }
    boolean isDispatchOnly() { return dispatchOnly; }

    void deferredMatching(boolean value) { deferredMatching = value; }
    boolean deferredMatching() { return deferredMatching; }

    int cachedMatcherShard() { return cachedMatcherShard; }

    void cachedMatcherShard(int shard) {
        if (shard < 0) throw new IllegalArgumentException("matcher shard cannot be negative");
        cachedMatcherShard = shard;
    }
    boolean takeDispatchOnly() {
        boolean value = dispatchOnly;
        dispatchOnly = false;
        return value;
    }
    void countPipelinedSettlement() { pipelinedSettlementCounted = true; }
    boolean takePipelinedSettlementCounted() {
        boolean value = pipelinedSettlementCounted;
        pipelinedSettlementCounted = false;
        return value;
    }
    boolean commitFenceEstablished() {
        return commitFenceEstablished;
    }
    long commitFenceTimestamp() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceTimestamp;
    }
    long commitFenceClusterPosition() {
        if (!commitFenceEstablished) throw new IllegalStateException("matching commit fence is not established");
        return commitFenceClusterPosition;
    }

    enum Operation {
        PLACE,
        CANCEL,
        REPLACE,
        AMEND,
        TRIGGER,
        LIQUIDATION,
        LIQUIDATION_BATCH,
        SETTLEMENT
    }

    private enum ContinuationKind {
        SETTLEMENT,
        CANCEL,
        PLACE_ADMISSION
    }


    long coreSequence;
    boolean claimed;
    private long expectedLaneMask;
    private long completedLaneMask;
    private volatile CoreMatchingResult completedMatchingResult;
    private CoreMatchingResult matchingResult;
    /** Owner claims the route; Matcher releases it before publishing the result into this slot. */
    private volatile int submittedMatcherShard = -1;

    public int submittedMatcherShard() { return submittedMatcherShard; }

    public void claimMatcherSubmission(int shardId) {
        if (!claimed || shardId < 0 || submittedMatcherShard != -1 || completedMatchingResult != null)
            throw new IllegalStateException("matcher command token is already routed or inactive");
        submittedMatcherShard = shardId;
    }

    @Override
    public void releaseMatcherSubmission(int shardId) {
        if (submittedMatcherShard != shardId) {
            throw new IllegalStateException("matcher completion belongs to another shard");
        }
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
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlanBuffer() {
        if (!claimed) throw new IllegalStateException("unclaimed settlement slot");
        if (reusableSettlementPlan == null)
            reusableSettlementPlan = new com.surprising.aeron.service.state.MatcherSettlementPlan();
        return reusableSettlementPlan;
    }
    long coreSequence() { return claimed ? coreSequence : 0; }
    long expectedLaneMask() { return expectedLaneMask; }
    long completedLaneMask() { return completedLaneMask; }
    CoreMatchingResult matchingResult() { return matchingResult; }
    /** Final write by the Matcher: subsequent Owner work reads this slot directly. */
    public void publishMatcherResult(int shardId, CoreMatchingResult result) {
        if (result == null || result.nativeCommand().coreSequence() != coreSequence
                || completedMatchingResult != null) {
            throw new IllegalStateException("invalid matcher result publication");
        }
        releaseMatcherSubmission(shardId);
        completedMatchingResult = result;
    }

    void publishMatchingCompletion(CoreMatchingResult result) {
        if (result == null || result.nativeCommand().coreSequence() != coreSequence) {
            throw new IllegalStateException("invalid matching completion");
        }
        if (completedMatchingResult == null) completedMatchingResult = result;
    }

    public CoreMatchingResult takeMatchingCompletion() {
        CoreMatchingResult result = completedMatchingResult;
        // An empty probe must not erase a concurrent Matcher publication.
        if (result != null) completedMatchingResult = null;
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

    void clear() {
        if (reusableSettlementPlan != null) reusableSettlementPlan.clearReferences();
        // CommandSlot is overwritten by initialize() before the slot can be observed
        // again.  Clearing every reference here duplicates that work on the owner hot path
        // and only reduces retention during the short free interval.
        clearLifecycleState();
    }

    private void clearLifecycleState() {
        controlWork = null;
        controlPending = false;
        controlStartedNanos = 0;
        commitEvent = null;
        resultData = null;
        claimed = false;
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
        placeAdmission = null;
        cachedMatcherShard = -1;
    }
}
