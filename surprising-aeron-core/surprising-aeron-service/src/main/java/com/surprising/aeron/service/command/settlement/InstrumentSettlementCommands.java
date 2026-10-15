package com.surprising.aeron.service.command.settlement;

import com.surprising.aeron.service.orchestration.TradingCoreRuntime;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.state.RuntimeLifecycleSettlement;
import com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
public final class InstrumentSettlementCommands {
    private final TradingCoreRuntime owner;

    public InstrumentSettlementCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = owner.lifecycleProgress(command.instrumentId());
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.settlementLifecycleOrders(
                0, command.instrumentId(), command.cursorOrderId(), command.maxOrders());
        if (progress == null || !progress.ordersComplete()) {
            owner.setCommandChangedOrderIds(boxedOrderIds(chunk.orders()));
            owner.addChangedUsersFromOrders(chunk.orders());
            return;
        }
        owner.setCommandChangedOrderIds(List.of());
    }

    /** Primitive pagination for the command result path; keeps no boxed user page alive. */
    public void addSettlementUsersToResult(String instrumentId, long cursorUserId, int maxUsers) {
        if (owner.runtimeState().systemLedgerStarted())
            throw new IllegalStateException("active settlement uses its Ledger route receipt");
        owner.beginChangedUsers();
        if (maxUsers <= 0) return;
        long userId = owner.positionUserIndex().higherUserId(instrumentId, cursorUserId);
        int count = 0;
        while (userId != 0 && count < maxUsers) {
            owner.addChangedUser(userId);
            count++;
            userId = owner.positionUserIndex().higherUserId(instrumentId, userId);
        }
    }

    public void settleInstrumentRuntime(com.surprising.aeron.protocol.SettleInstrumentCommand command,
                                        UUID commandId) {
        addSettlementUsersToResult(command.instrumentId(), command.cursorUserId(), command.maxUsers());
        long beforeRevision = owner.runtimeState().revision();
        owner.setCommandSettlementProgress(RuntimeLifecycleSettlement.applyRuntime(command,
                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()), commandId,
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities()));
        if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
    }

    public RuntimeLifecycleSettlementContinuation.SettlementWork beginAsyncSettlement(
            com.surprising.aeron.protocol.SettleInstrumentCommand command, UUID commandId) {
        return beginAsyncSettlement(null, command, commandId);
    }

    public RuntimeLifecycleSettlementContinuation.SettlementWork beginAsyncSettlement(
            RuntimeLifecycleSettlementContinuation.SettlementWork reuse,
            com.surprising.aeron.protocol.SettleInstrumentCommand command, UUID commandId) {
        return RuntimeLifecycleSettlementContinuation.prepare(reuse, command,
                owner.positionUserIndex().usersAfter(command.instrumentId(), command.cursorUserId()), commandId,
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
    }

    private static List<Long> boxedOrderIds(List<CancellationOrder> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        long[] values = new long[orders.size()];
        for (int index = 0; index < values.length; index++) values[index] = orders.get(index).orderId();
        return com.surprising.aeron.service.command.ImmutableLongArrayList.takeOwnership(values);
    }
}
