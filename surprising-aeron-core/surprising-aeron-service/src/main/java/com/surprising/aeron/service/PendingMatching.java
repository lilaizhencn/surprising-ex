package com.surprising.aeron.service;

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

final class PendingMatching {
    private long sequence;
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
    private com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent;
    private LaneCancelEvent cancelEvent;
    private LaneReplaceEvent replaceEvent;
    private com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan;
    private long settlementApplyStartNanos;
    private PlaceAdmissionEvent placeAdmission;
    private CoreMatchingOrder admittedMatchingOrder;
    private boolean matchingSubmitted;
    private boolean settlementReady;
    private boolean dispatchOnly;
    private boolean pipelinedSettlementCounted;

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
        this.operation = operation;
        this.command = command;
        this.fingerprint = fingerprint;
        this.preMatchingCancellationOrderIds = List.copyOf(preMatchingCancellationOrderIds);
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
        settlementEvent = null;
        cancelEvent = null;
        replaceEvent = null;
        settlementPlan = null;
        settlementApplyStartNanos = 0;
        placeAdmission = null;
        admittedMatchingOrder = null;
        matchingSubmitted = false;
        settlementReady = false;
        dispatchOnly = false;
        pipelinedSettlementCounted = false;
        return this;
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        Objects.requireNonNull(nextCommand, "nextCommand");
        if (!nextCommand.header().equals(command.header())) {
            return new PendingMatching(this, nextCommand);
        }
        command = nextCommand;
        decodedCommand = DecodedMatchingCommand.decode(nextCommand);
        return this;
    }

    private PendingMatching(PendingMatching source, CoreMessage nextCommand) {
        sequence = source.sequence;
        operation = source.operation;
        command = nextCommand;
        fingerprint = source.fingerprint;
        preMatchingCancellationOrderIds = source.preMatchingCancellationOrderIds;
        beforeProjection = source.beforeProjection;
        beforeBusinessStateHash = source.beforeBusinessStateHash;
        beforeFundsStateHash = source.beforeFundsStateHash;
        fundsDelta = source.fundsDelta;
        decodedCommand = DecodedMatchingCommand.decode(nextCommand);
        admission = source.admission;
        capacityReservation = source.capacityReservation;
        pendingStateHash = source.pendingStateHash;
        commitFenceTimestamp = source.commitFenceTimestamp;
        commitFenceClusterPosition = source.commitFenceClusterPosition;
        commitFenceEstablished = source.commitFenceEstablished;
        settlementEvent = source.settlementEvent;
        cancelEvent = source.cancelEvent;
        replaceEvent = source.replaceEvent;
        settlementPlan = source.settlementPlan;
        settlementApplyStartNanos = source.settlementApplyStartNanos;
        placeAdmission = source.placeAdmission;
        admittedMatchingOrder = source.admittedMatchingOrder;
        matchingSubmitted = source.matchingSubmitted;
        settlementReady = source.settlementReady;
        dispatchOnly = source.dispatchOnly;
        pipelinedSettlementCounted = source.pipelinedSettlementCounted;
    }

    PendingMatching withPreMatchingCancellations(List<Long> orderIds) {
        preMatchingCancellationOrderIds = List.copyOf(orderIds);
        return this;
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
    ResolvedMatchingAdmission admission() { return admission; }
    CoreAdmissionReservation takeCapacityReservation() {
        CoreAdmissionReservation value = capacityReservation;
        capacityReservation = null;
        return value;
    }
    long pendingStateHash() { return pendingStateHash; }
    com.surprising.aeron.service.state.MatcherSettlementEvent settlementEvent() { return settlementEvent; }
    com.surprising.aeron.service.state.MatcherSettlementEvent takeSettlementEvent() {
        var value = settlementEvent;
        settlementEvent = null;
        return value;
    }
    LaneCancelEvent cancelEvent() { return cancelEvent; }
    LaneCancelEvent takeCancelEvent() {
        LaneCancelEvent value = cancelEvent;
        cancelEvent = null;
        return value;
    }
    LaneReplaceEvent replaceEvent() { return replaceEvent; }
    LaneReplaceEvent takeReplaceEvent() {
        LaneReplaceEvent value = replaceEvent;
        replaceEvent = null;
        return value;
    }
    com.surprising.aeron.service.state.MatcherSettlementPlan settlementPlan() { return settlementPlan; }
    long settlementApplyStartNanos() { return settlementApplyStartNanos; }
    PlaceAdmissionEvent placeAdmission() { return placeAdmission; }
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
    void admissionCompleted(CoreMatchingOrder matchingOrder) {
        if (matchingOrder == null || placeAdmission == null || admittedMatchingOrder != null) {
            throw new IllegalStateException("invalid completed place admission");
        }
        admittedMatchingOrder = matchingOrder;
    }
    CoreMatchingOrder admittedMatchingOrder() { return admittedMatchingOrder; }
    boolean isMatchingSubmitted() { return matchingSubmitted; }
    void matchingSubmitted() {
        if (matchingSubmitted) throw new IllegalStateException("matching command was submitted twice");
        matchingSubmitted = true;
    }
    boolean settlementReady() { return settlementReady; }
    void markSettlementReady() {
        if (settlementEvent == null && cancelEvent == null && replaceEvent == null) {
            throw new IllegalStateException("lane continuation completion has no event");
        }
        settlementReady = true;
    }
    void replace(LaneReplaceEvent event, long applyStartNanos) {
        if (event == null || replaceEvent != null || settlementEvent != null
                || operation != Operation.REPLACE && operation != Operation.AMEND) {
            throw new IllegalStateException("invalid replace continuation");
        }
        replaceEvent = event;
        settlementApplyStartNanos = applyStartNanos;
    }
    void cancel(LaneCancelEvent event, long applyStartNanos) {
        if (event == null || cancelEvent != null || settlementEvent != null || operation != Operation.CANCEL) {
            throw new IllegalStateException("invalid cancel continuation");
        }
        cancelEvent = event;
        settlementApplyStartNanos = applyStartNanos;
    }
    void settlement(com.surprising.aeron.service.state.MatcherSettlementEvent event,
                    com.surprising.aeron.service.state.MatcherSettlementPlan plan,
                    long applyStartNanos) {
        if (event == null || plan == null || settlementEvent != null) {
            throw new IllegalStateException("invalid matcher settlement continuation");
        }
        settlementEvent = event;
        settlementPlan = plan;
        settlementApplyStartNanos = applyStartNanos;
        settlementReady = false;
    }
    void dispatchOnly() { dispatchOnly = true; }
    boolean isDispatchOnly() { return dispatchOnly; }
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

}
