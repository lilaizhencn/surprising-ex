package com.surprising.aeron.service.orchestration;
import com.surprising.aeron.service.command.order.DecodedMatchingCommand;
import com.surprising.aeron.service.command.order.ResolvedMatchingAdmission;
import com.surprising.aeron.service.command.ImmutableLongArrayList;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.PlaceAdmissionEvent;
import com.surprising.aeron.service.state.LaneCancelEvent;
import com.surprising.aeron.service.state.LaneReplaceEvent;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import java.util.List;
import java.util.Objects;

class PendingMatching {
    private long sequence;
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
    private CoreAdmissionReservation capacityReservation;
    private long pendingStateHash;
    private long commitFenceTimestamp;
    private long commitFenceClusterPosition;
    private boolean commitFenceEstablished;
    /**
     * Exactly one asynchronous continuation can own a command at a time.  Keep one tagged
     * reference instead of four mostly-null object fields; the continuation payload itself still
     * remains pooled by its owning dispatcher.
     */
    private ContinuationKind continuationKind;
    private Object continuation;
    private com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan;
    private long settlementApplyStartNanos;
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

    PendingMatching() {
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of(), beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, List.of(), beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command));
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, null);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, null);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand,
                admission);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        initialize(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                decodedCommand, admission);
    }

    PendingMatching initialize(long sequence, Operation operation, CoreMessage command,
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
        this.sequence = sequence;
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
        capacityReservation = null;
        pendingStateHash = 0;
        commitFenceTimestamp = 0;
        commitFenceClusterPosition = 0;
        commitFenceEstablished = false;
        continuationKind = null;
        continuation = null;
        settlementPlan = null;
        settlementApplyStartNanos = 0;
        admittedMatchingOrder = null;
        matchingSubmitted = false;
        crossShardCancellationStarted = false;
        clusterIndependent = false;
        partitionLaneMask = 0;
        realtimeTakerOrder = null;
        dispatchOnly = false;
        pipelinedSettlementCounted = false;
        deferredMatching = false;
        return this;
    }

    PendingMatching withPreMatchingCancellations(List<Long> orderIds) {
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

    PendingMatching withAdmission(ResolvedMatchingAdmission nextAdmission) {
        admission = nextAdmission;
        return this;
    }

    PendingMatching withCapacityReservation(CoreAdmissionReservation reservation) {
        capacityReservation = reservation;
        return this;
    }

    PendingMatching withPendingStateHash(long stateHash) {
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

    long sequence() { return sequence; }
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
    CoreAdmissionReservation takeCapacityReservation() {
        CoreAdmissionReservation value = capacityReservation;
        capacityReservation = null;
        return value;
    }
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
    LaneReplaceEvent replaceEvent() {
        return continuationKind == ContinuationKind.REPLACE ? (LaneReplaceEvent) continuation : null;
    }
    LaneReplaceEvent takeReplaceEvent() {
        LaneReplaceEvent value = replaceEvent();
        clearContinuation(ContinuationKind.REPLACE);
        return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    long settlementApplyStartNanos() { return settlementApplyStartNanos; }
    PlaceAdmissionEvent placeAdmission() {
        return continuationKind == ContinuationKind.PLACE_ADMISSION
                ? (PlaceAdmissionEvent) continuation : null;
    }
    boolean hasLaneContinuation() {
        return continuationKind == ContinuationKind.SETTLEMENT
                || continuationKind == ContinuationKind.CANCEL
                || continuationKind == ContinuationKind.REPLACE;
    }
    boolean laneContinuationComplete() {
        if (!hasLaneContinuation()) return false;
        return switch (continuationKind) {
            case SETTLEMENT -> settlementEvent().complete();
            case CANCEL -> cancelEvent().complete();
            case REPLACE -> replaceEvent().complete();
            default -> false;
        };
    }
    PlaceAdmissionEvent takePlaceAdmission() {
        PlaceAdmissionEvent value = continuationKind == ContinuationKind.PLACE_ADMISSION
                ? (PlaceAdmissionEvent) continuation : null;
        clearContinuation(ContinuationKind.PLACE_ADMISSION);
        return value;
    }
    void placeAdmission(PlaceAdmissionEvent event) {
        if (event == null || continuation != null || operation != Operation.PLACE) {
            throw new IllegalStateException("invalid place admission continuation");
        }
        continuationKind = ContinuationKind.PLACE_ADMISSION;
        continuation = event;
    }
    void admissionCompleted(CoreMatchingOrder matchingOrder) {
        if (matchingOrder == null || placeAdmission() == null || admittedMatchingOrder != null) {
            throw new IllegalStateException("invalid completed place admission");
        }
        admittedMatchingOrder = matchingOrder;
    }
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
    void replace(LaneReplaceEvent event, long applyStartNanos) {
        if (event == null || continuation != null
                || operation != Operation.REPLACE && operation != Operation.AMEND) {
            throw new IllegalStateException("invalid replace continuation");
        }
        continuationKind = ContinuationKind.REPLACE;
        continuation = event;
        settlementApplyStartNanos = applyStartNanos;
    }
    void cancel(LaneCancelEvent event, long applyStartNanos) {
        if (event == null || continuation != null
                || operation != Operation.CANCEL && operation != Operation.REPLACE && operation != Operation.AMEND) {
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
        if (continuationKind != expected) return;
        continuationKind = null;
        continuation = null;
    }

    /** Clears the command-owned references when its fixed sequence slot is recycled. */
    void clearReferences() {
        sequence = 0;
        orderBatch = null;
        operation = null;
        command = null;
        fingerprint = null;
        preMatchingCancellationOrderIds = List.of();
        beforeProjection = null;
        fundsDelta = null;
        decodedCommand = null;
        admission = null;
        capacityReservation = null;
        continuationKind = null;
        continuation = null;
        settlementPlan = null;
        admittedMatchingOrder = null;
        realtimeTakerOrder = null;
        deferredMatching = false;
    }
    void dispatchOnly() { dispatchOnly = true; }
    boolean isDispatchOnly() { return dispatchOnly; }

    void deferredMatching(boolean value) { deferredMatching = value; }
    boolean deferredMatching() { return deferredMatching; }
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
        REPLACE,
        PLACE_ADMISSION
    }

}
