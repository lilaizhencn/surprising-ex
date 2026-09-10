package com.surprising.aeron.service.execution;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import java.util.List;

/** 余额调整与资金转入转出命令；维护转账幂等状态。 */
final class BalanceTransferCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    BalanceTransferCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void executeAdjustBalance(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        adjustBalance(message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
    }

    private void adjustBalance(long userId, com.surprising.aeron.protocol.BalanceAdjustmentCommand command) {
        if (owner.runtimeState.asynchronousCommands()) {
            var work = new com.surprising.aeron.service.state.AccountBalanceAdjustment(
                    owner.runtimeState, owner.identities, userId, command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                owner.commits.requestCommitPublication();
                return true;
            });
        } else {
            RuntimeCommandProcessor.adjustBalance(owner.runtimeState, owner.identities, userId, command);
            owner.commits.requestCommitPublication();
        }
    }

    void executeTransferOut(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (owner.runtimeState.asynchronousCommands()) {
            var work = new com.surprising.aeron.service.state.AccountTransferOut(
                    owner.runtimeState, owner.identities, message.header().userId(), command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                completeTransferPublication();
                return true;
            });
        } else {
            RuntimeCommandProcessor.transferOut(owner.runtimeState, owner.identities, message.header().userId(), command);
            completeTransferPublication();
        }
    }

    private void completeTransferPublication() {
        owner.cachedTransferHash = TradingCoreRuntime.computeTransferHash(owner.runtimeState.pendingTransfersSnapshot());
        owner.commits.requestCommitPublication();
    }

    void executeTransferIn(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        var command = TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe());
        if (owner.productLine != command.targetProductLine())
            throw new com.surprising.aeron.service.state.CoreStateRejectedException(
                    "PRODUCT_LINE_MISMATCH", "transfer target product line mismatch");
        adjustBalance(message.header().userId(), new com.surprising.aeron.protocol.BalanceAdjustmentCommand(
                command.asset(), command.amountUnits()));
    }

    void executeCompleteTransfer(CoreMessage message, long clusterTimestamp) {
        RuntimeCommandProcessor.completeTransfer(owner.runtimeState, message.header().userId(),
                TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId());
        owner.cachedTransferHash = TradingCoreRuntime.computeTransferHash(owner.runtimeState.pendingTransfersSnapshot());
        owner.commits.requestCommitPublication();
    }
}
