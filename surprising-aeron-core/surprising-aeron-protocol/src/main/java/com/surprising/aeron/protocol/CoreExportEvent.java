package com.surprising.aeron.protocol;

import java.util.List;
import java.util.UUID;

/**
 * Ordered command fact retained for a future history sink.
 *
 * <p>The event deliberately contains facts produced by the command itself and funds postings.
 * Reconstructed current-state views and deletion tombstones do not belong in the transaction path.</p>
 */
public record CoreExportEvent(
        long exportSequence,
        long appliedCommandCount,
        UUID commandId,
        CoreMessageType commandType,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        long userId,
        byte[] commandPayload,
        List<CoreExecutionView> executions,
        List<CoreFundingPaymentView> fundingPayments,
        int routeVersion,
        long committedCoreSequence,
        long clusterPosition,
        List<CoreFundsPostingView> fundsPostings,
        CommandFingerprint commandFingerprint,
        TerminalIds terminalIds,
        long previousCoreSequence,
        long coreSequence,
        long previousProjectionSequence,
        long projectionSequence,
        CoreFundingProgressView fundingProgress,
        CoreSettlementProgressView settlementProgress) {

    public CoreExportEvent {
        if (exportSequence <= 0 || appliedCommandCount <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandStatus == null || resultCode == null
                || commandPayload == null || executions == null || fundingPayments == null
                || routeVersion != CoreRoute.DEFAULT.version() || committedCoreSequence != appliedCommandCount
                || clusterPosition < 0 || fundsPostings == null || commandFingerprint == null
                || terminalIds == null || previousCoreSequence < 0 || coreSequence < previousCoreSequence
                || previousProjectionSequence < 0 || projectionSequence < previousProjectionSequence) {
            throw new IllegalArgumentException("invalid core export event");
        }
        commandPayload = commandPayload.clone();
        executions = List.copyOf(executions);
        fundingPayments = List.copyOf(fundingPayments);
        fundsPostings = List.copyOf(fundsPostings);
        requireNonZeroFingerprint(commandFingerprint);
    }

    public record TerminalIds(List<Long> orderIds, List<Long> liquidationIds, List<Long> triggerOrderIds) {
        public TerminalIds {
            orderIds = canonicalIds(orderIds, "terminal order ID");
            liquidationIds = canonicalIds(liquidationIds, "terminal liquidation ID");
            triggerOrderIds = canonicalIds(triggerOrderIds, "terminal trigger order ID");
        }

        public static TerminalIds empty() {
            return new TerminalIds(List.of(), List.of(), List.of());
        }

        private static List<Long> canonicalIds(List<Long> values, String description) {
            if (values == null) throw new IllegalArgumentException(description + " list is required");
            long previous = 0;
            for (Long value : values) {
                if (value == null || value <= previous) {
                    throw new IllegalArgumentException(description + " list is invalid or non-canonical");
                }
                previous = value;
            }
            return List.copyOf(values);
        }
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }

    public byte[] commandPayloadUnsafe() {
        return commandPayload;
    }

    private static void requireNonZeroFingerprint(CommandFingerprint fingerprint) {
        for (byte value : fingerprint.bytes()) {
            if (value != 0) return;
        }
        throw new IllegalArgumentException("command fingerprint must not be zero");
    }
}
