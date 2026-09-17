package com.surprising.aeron.service.command.leverage;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.AccountLeverageChange;
import com.surprising.aeron.service.state.DerivativeAccountCommandProcessor;

/** 衍生品杠杆命令；只处理衍生品账户的杠杆变更。 */
public final class LeverageCommands {
    private final CommandResultContext owner;

    public LeverageCommands(CommandResultContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeUpdateLeverage(CoreMessage message, long clusterTimestamp) {
        long userId = message.header().userId();
        owner.setSingleChangedUser(userId);
        var command = TradingCommandCodec.decodeUpdateLeverage(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            long beforeRevision = owner.runtimeState().revision();
            var work = AccountLeverageChange.prepare(owner.reusableLeverageChange(),
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.deferLeverageChangeControl(work, beforeRevision);
        } else if (DerivativeAccountCommandProcessor.updateLeverage(
                owner.runtimeState(), owner.identities(), userId, command)) {
            owner.requestCommitPublication();
        }
    }
}
