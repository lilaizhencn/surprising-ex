package com.surprising.aeron.service.command.leverage;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.state.account.DerivativeAccountCommandProcessor;

/** 衍生品杠杆命令；只处理衍生品账户的杠杆变更。 */
public final class LeverageCommands {
    private final TradingCoreRuntime owner;

    public LeverageCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeUpdateLeverage(CoreMessage message, long clusterTimestamp) {
        long userId = message.header().userId();
        owner.resultBuilder.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            long beforeRevision = owner.runtimeState().revision();
            owner.directCommand.deferLeverageChange(owner, userId, command, beforeRevision);
        } else if (DerivativeAccountCommandProcessor.updateLeverage(
                owner.runtimeState(), owner.identities(), userId, command)) {
            owner.requestCommitPublication();
        }
    }
}
