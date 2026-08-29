package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import java.util.List;
import java.util.Objects;

record PendingMatching(long sequence, Operation operation, CoreMessage command, CommandFingerprint fingerprint,
                       List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                       long beforeBusinessStateHash, long beforeFundsStateHash,
                       RuntimeFundsDelta fundsDelta, DecodedMatchingCommand decodedCommand,
                       ResolvedMatchingAdmission admission) {

    PendingMatching(long sequence, Operation operation, CoreMessage command, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), List.of(), beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command), null);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta,
                DecodedMatchingCommand.decode(command), null);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, null);
    }

    PendingMatching(long sequence, Operation operation, CoreMessage command,
                    List<Long> preMatchingCancellationOrderIds, RuntimeProjectionPoint beforeProjection,
                    long beforeBusinessStateHash, long beforeFundsStateHash, RuntimeFundsDelta fundsDelta,
                    DecodedMatchingCommand decodedCommand, ResolvedMatchingAdmission admission) {
        this(sequence, operation, command, CommandFingerprint.of(command), preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand,
                admission);
    }

    PendingMatching {
        if (sequence <= 0 || operation == null || command == null || fingerprint == null
                || preMatchingCancellationOrderIds == null || beforeProjection == null
                || fundsDelta == null || decodedCommand == null
                || command.header().kind() != com.surprising.aeron.protocol.WireMessageKind.COMMAND) {
            throw new IllegalArgumentException("invalid pending matching request");
        }
        Objects.requireNonNull(command.header().commandId(), "commandId");
        preMatchingCancellationOrderIds = List.copyOf(preMatchingCancellationOrderIds);
    }

    PendingMatching withCommand(CoreMessage nextCommand) {
        return new PendingMatching(sequence, operation, nextCommand, fingerprint,
                preMatchingCancellationOrderIds, beforeProjection, beforeBusinessStateHash, beforeFundsStateHash,
                fundsDelta, DecodedMatchingCommand.decode(nextCommand), admission);
    }

    PendingMatching withPreMatchingCancellations(List<Long> orderIds) {
        return new PendingMatching(sequence, operation, command, fingerprint, orderIds, beforeProjection,
                beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand, admission);
    }

    PendingMatching withAdmission(ResolvedMatchingAdmission nextAdmission) {
        return new PendingMatching(sequence, operation, command, fingerprint, preMatchingCancellationOrderIds,
                beforeProjection, beforeBusinessStateHash, beforeFundsStateHash, fundsDelta, decodedCommand,
                nextAdmission);
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
