package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.state.DerivativeAccountCommandProcessor;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import java.util.List;

/** 衍生品保证金和杠杆命令；不处理现货资金冻结。 */
final class DerivativeAccountCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    DerivativeAccountCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void executeUpdatePositionMode(CoreMessage message, long clusterTimestamp) {
        long userId = message.header().userId();
        owner.resultBuilder.commandChangedUserIds = List.of(userId);
        var command = TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe());
        if (owner.runtimeState.asynchronousCommands()) {
            long beforeRevision = owner.runtimeState.revision();
            var work = new com.surprising.aeron.service.state.AccountPositionModeChange(owner.runtimeState, userId, command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                if (owner.runtimeState.revision() != beforeRevision) owner.commits.requestCommitPublication();
                return true;
            });
        } else if (DerivativeAccountCommandProcessor.updatePositionMode(owner.runtimeState, userId, command)) {
            owner.commits.requestCommitPublication();
        }
    }

    void executeAdjustPositionMargin(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        var command = TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe());
        if (owner.runtimeState.asynchronousCommands()) {
            var work = new com.surprising.aeron.service.state.AccountPositionMarginAdjustment(
                    owner.runtimeState, owner.identities, message.header().userId(), command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                owner.commits.requestCommitPublication();
                return true;
            });
        } else {
            DerivativeAccountCommandProcessor.adjustPositionMargin(owner.runtimeState, owner.identities,
                    message.header().userId(), command);
            owner.commits.requestCommitPublication();
        }
    }

    void executeUpdateLeverage(CoreMessage message, long clusterTimestamp) {
        owner.resultBuilder.commandChangedUserIds = List.of(message.header().userId());
        var command = TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe());
        if (owner.runtimeState.asynchronousCommands()) {
            long beforeRevision = owner.runtimeState.revision();
            var work = new com.surprising.aeron.service.state.AccountLeverageChange(
                    owner.runtimeState, owner.identities, message.header().userId(), command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                if (owner.runtimeState.revision() != beforeRevision) owner.commits.requestCommitPublication();
                return true;
            });
        } else if (DerivativeAccountCommandProcessor.updateLeverage(owner.runtimeState, owner.identities,
                message.header().userId(), command)) {
            owner.commits.requestCommitPublication();
        }
    }
}
