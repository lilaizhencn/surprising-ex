package com.surprising.aeron.service.command.position;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.AccountPositionMarginAdjustment;
import com.surprising.aeron.service.state.AccountPositionModeChange;
import com.surprising.aeron.service.state.DerivativeAccountCommandProcessor;

/** 衍生品持仓模式和持仓保证金命令；不处理现货资金冻结或杠杆变更。 */
public final class PositionCommands {
    private final CommandResultContext owner;

    public PositionCommands(CommandResultContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeUpdatePositionMode(CoreMessage message, long clusterTimestamp) {
        long userId = message.header().userId();
        owner.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeUpdatePositionMode(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            long beforeRevision = owner.runtimeState().revision();
            var work = AccountPositionModeChange.prepare(owner.reusablePositionModeChange(),
                    owner.runtimeState(), userId, command);
            owner.deferPositionModeChangeControl(work, beforeRevision);
        } else if (DerivativeAccountCommandProcessor.updatePositionMode(owner.runtimeState(), userId, command)) {
            owner.requestCommitPublication();
        }
    }

    public void executeAdjustPositionMargin(CoreMessage message, long clusterTimestamp) {
        long userId = message.header().userId();
        owner.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeAdjustPositionMargin(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            var work = AccountPositionMarginAdjustment.prepare(owner.reusablePositionMarginAdjustment(),
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.deferPositionMarginAdjustmentControl(work);
        } else {
            DerivativeAccountCommandProcessor.adjustPositionMargin(
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.requestCommitPublication();
        }
    }
}
