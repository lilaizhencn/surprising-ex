package com.surprising.aeron.service.execution;


import com.surprising.aeron.service.state.RuntimeSettlementProcessor;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 交割与期权等币对到期结算：沿用产品结算处理器，并登记受影响账户。 */
final class InstrumentSettlementCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    InstrumentSettlementCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void applySettlementChangedIds(com.surprising.aeron.protocol.SettleInstrumentCommand command) {
        var progress = owner.runtimeLifecycleProgress(command.symbol());
        TradingCoreRuntime.LifecycleOrderChunk chunk = owner.lifecycleOrders(0, command.symbol(), command.cursorOrderId(), command.maxOrders());
        if (progress == null || !progress.ordersComplete()) {
            owner.resultBuilder.commandChangedOrderIds = chunk.orders().stream().mapToLong(CoreOrderState::orderId).boxed().toList();
            owner.resultBuilder.commandChangedUserIds = chunk.orders().stream().map(CoreOrderState::userId).distinct().toList();
            if (!chunk.more()) {
                owner.resultBuilder.commandChangedUserIds = TradingCoreRuntime.appendDistinct(owner.resultBuilder.commandChangedUserIds,
                        settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers()));
            }
            return;
        }
        owner.resultBuilder.commandChangedOrderIds = List.of();
        owner.resultBuilder.commandChangedUserIds = settlementUsers(command.symbol(), command.cursorUserId(), command.maxUsers());
    }

    List<Long> settlementUsers(String symbol, long cursorUserId, int maxUsers) {
        List<Long> selected = new ArrayList<>(Math.min(maxUsers, 64));
        for (Long userId : owner.positionUserIndex.usersAfter(symbol, cursorUserId)) {
            if (userId == null || userId <= cursorUserId) continue;
            if (selected.size() == maxUsers) break;
            selected.add(userId);
        }
        return List.copyOf(selected);
    }

    void settleInstrumentRuntime(com.surprising.aeron.protocol.SettleInstrumentCommand command,
                                         UUID commandId) {
        long beforeRevision = owner.runtimeState.revision();
        owner.resultBuilder.commandSettlementProgress = RuntimeSettlementProcessor.applyRuntime(command,
                owner.positionUserIndex.usersAfter(command.symbol(), command.cursorUserId()), commandId, owner.activeOrderIndex,
                owner.runtimeState, owner.identities);
        if (owner.runtimeState.revision() != beforeRevision) owner.commits.requestCommitPublication();
    }

    com.surprising.aeron.service.state.RuntimeSettlementProcessor.SettlementWork beginAsyncSettlement(
            com.surprising.aeron.protocol.SettleInstrumentCommand command, UUID commandId) {
        return RuntimeSettlementProcessor.prepareAsync(command,
                owner.positionUserIndex.usersAfter(command.symbol(), command.cursorUserId()), commandId,
                owner.activeOrderIndex, owner.runtimeState, owner.identities);
    }

}
