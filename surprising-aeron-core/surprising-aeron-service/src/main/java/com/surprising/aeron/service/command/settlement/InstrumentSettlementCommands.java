package com.surprising.aeron.service.command.settlement;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.service.state.RuntimeLifecycleSettlement;
import com.surprising.aeron.service.state.RuntimeLifecycleSettlementContinuation;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
public final class InstrumentSettlementCommands {
    private final SettlementCommandContext owner;

    public InstrumentSettlementCommands(SettlementCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = owner.lifecycleProgress(command.symbol());
        SettlementCommandContext.LifecycleOrderPage chunk = owner.settlementLifecycleOrders(
                0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        if (progress == null || !progress.ordersComplete()) {
            owner.setCommandChangedOrderIds(boxedOrderIds(chunk.orders()));
            owner.addChangedUsersFromOrders(chunk.orders());
            if (!chunk.more()) {
                addSettlementUsersToResult(command.symbol(), command.cursorUserId(), command.maxUsers());
            }
            return;
        }
        owner.setCommandChangedOrderIds(List.of());
        addSettlementUsersToResult(command.symbol(), command.cursorUserId(), command.maxUsers());
    }

    public List<Long> settlementUsers(String symbol, long cursorUserId, int maxUsers) {
        List<Long> selected = new ArrayList<>(Math.min(maxUsers, 64));
        for (Long userId : owner.positionUserIndex().usersAfter(symbol, cursorUserId)) {
            if (userId == null || userId <= cursorUserId) continue;
            if (selected.size() == maxUsers) break;
            selected.add(userId);
        }
        return List.copyOf(selected);
    }

    /** Primitive pagination for the command result path; keeps no boxed user page alive. */
    public void addSettlementUsersToResult(String symbol, long cursorUserId, int maxUsers) {
        owner.beginChangedUsers();
        if (maxUsers <= 0) return;
        long userId = owner.positionUserIndex().higherUserId(symbol, cursorUserId);
        int count = 0;
        while (userId != 0 && count < maxUsers) {
            owner.addChangedUser(userId);
            count++;
            userId = owner.positionUserIndex().higherUserId(symbol, userId);
        }
    }

    public void settleInstrumentRuntime(com.surprising.aeron.protocol.SettleInstrumentCommand command,
                                        UUID commandId) {
        long beforeRevision = owner.runtimeState().revision();
        owner.setCommandSettlementProgress(RuntimeLifecycleSettlement.applyRuntime(command,
                owner.positionUserIndex().usersAfter(command.symbol(), command.cursorUserId()), commandId,
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
                owner.positionUserIndex().usersAfter(command.symbol(), command.cursorUserId()), commandId,
                owner.activeOrderIndex(), owner.runtimeState(), owner.identities());
    }

    private static List<Long> boxedOrderIds(List<? extends CoreOrderState> orders) {
        if (orders == null || orders.isEmpty()) return List.of();
        long[] values = new long[orders.size()];
        for (int index = 0; index < values.length; index++) values[index] = orders.get(index).orderId();
        return com.surprising.aeron.service.command.ImmutableLongArrayList.takeOwnership(values);
    }
}
