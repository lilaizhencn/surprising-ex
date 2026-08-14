package com.surprising.aeron.protocol;

import java.util.UUID;
import java.util.List;

public record CoreExportEvent(
        long exportSequence,
        long appliedCommandCount,
        long businessStateHash,
        UUID commandId,
        CoreMessageType commandType,
        ResponseStatus commandStatus,
        CoreResultCode resultCode,
        long userId,
        byte[] commandPayload,
        List<CoreUserStateView> changedUsers,
        List<CoreOrderStateView> changedOrders,
        List<CoreExecutionView> executions,
        List<CoreFundingPaymentView> fundingPayments,
        List<CoreLiquidationView> changedLiquidations,
        List<CoreTreasuryAssetView> changedTreasuryAssets,
        List<CoreTriggerOrderStateView> changedTriggerOrders) {

    public CoreExportEvent {
        if (exportSequence <= 0 || appliedCommandCount <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND || commandStatus == null || resultCode == null
                || commandPayload == null || changedUsers == null || changedOrders == null || executions == null
                || fundingPayments == null || changedLiquidations == null || changedTreasuryAssets == null
                || changedTriggerOrders == null) {
            throw new IllegalArgumentException("invalid core export event");
        }
        commandPayload = commandPayload.clone();
        changedUsers = List.copyOf(changedUsers);
        changedOrders = List.copyOf(changedOrders);
        executions = List.copyOf(executions);
        fundingPayments = List.copyOf(fundingPayments);
        changedLiquidations = List.copyOf(changedLiquidations);
        changedTreasuryAssets = List.copyOf(changedTreasuryAssets);
        changedTriggerOrders = List.copyOf(changedTriggerOrders);
    }

    public CoreExportEvent(long exportSequence, long appliedCommandCount, long businessStateHash,
                           UUID commandId, CoreMessageType commandType, ResponseStatus commandStatus,
                           CoreResultCode resultCode, long userId, byte[] commandPayload) {
        this(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                commandStatus, resultCode, userId, commandPayload, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    public CoreExportEvent(long exportSequence, long appliedCommandCount, long businessStateHash,
                           UUID commandId, CoreMessageType commandType, ResponseStatus commandStatus,
                           CoreResultCode resultCode, long userId, byte[] commandPayload,
                           List<CoreUserStateView> changedUsers, List<CoreOrderStateView> changedOrders,
                           List<CoreExecutionView> executions) {
        this(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                commandStatus, resultCode, userId, commandPayload, changedUsers, changedOrders,
                executions, List.of(), List.of(), List.of(), List.of());
    }

    public CoreExportEvent(long exportSequence, long appliedCommandCount, long businessStateHash,
                           UUID commandId, CoreMessageType commandType, ResponseStatus commandStatus,
                           CoreResultCode resultCode, long userId, byte[] commandPayload,
                           List<CoreUserStateView> changedUsers, List<CoreOrderStateView> changedOrders,
                           List<CoreExecutionView> executions, List<CoreFundingPaymentView> fundingPayments) {
        this(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                commandStatus, resultCode, userId, commandPayload, changedUsers, changedOrders,
                executions, fundingPayments, List.of(), List.of(), List.of());
    }

    public CoreExportEvent(long exportSequence, long appliedCommandCount, long businessStateHash,
                           UUID commandId, CoreMessageType commandType, ResponseStatus commandStatus,
                           CoreResultCode resultCode, long userId, byte[] commandPayload,
                           List<CoreUserStateView> changedUsers, List<CoreOrderStateView> changedOrders,
                           List<CoreExecutionView> executions, List<CoreFundingPaymentView> fundingPayments,
                           List<CoreLiquidationView> changedLiquidations,
                           List<CoreTreasuryAssetView> changedTreasuryAssets) {
        this(exportSequence, appliedCommandCount, businessStateHash, commandId, commandType,
                commandStatus, resultCode, userId, commandPayload, changedUsers, changedOrders,
                executions, fundingPayments, changedLiquidations, changedTreasuryAssets, List.of());
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }
}
