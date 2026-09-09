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
        RuntimeCommandProcessor.adjustBalance(owner.runtimeState, owner.identities,
                message.header().userId(), TradingCommandCodec.decodeBalanceAdjustment(message.payloadUnsafe()));
        owner.commits.requestCommitPublication();
    }

    void executeTransferOut(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        RuntimeCommandProcessor.transferOut(owner.runtimeState, owner.identities,
                message.header().userId(), TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe()));
        owner.cachedTransferHash = TradingCoreRuntime.computeTransferHash(owner.runtimeState.pendingTransfersSnapshot());
        owner.commits.requestCommitPublication();
    }

    void executeTransferIn(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        RuntimeCommandProcessor.transferIn(owner.runtimeState, owner.identities,
                message.header().userId(), TradingCommandCodec.decodeTransferFunds(message.payloadUnsafe()));
        owner.commits.requestCommitPublication();
    }

    void executeCompleteTransfer(CoreMessage message, long clusterTimestamp) {
        RuntimeCommandProcessor.completeTransfer(owner.runtimeState, message.header().userId(),
                TradingCommandCodec.decodeCompleteTransfer(message.payloadUnsafe()).transferId());
        owner.cachedTransferHash = TradingCoreRuntime.computeTransferHash(owner.runtimeState.pendingTransfersSnapshot());
        owner.commits.requestCommitPublication();
    }
}
