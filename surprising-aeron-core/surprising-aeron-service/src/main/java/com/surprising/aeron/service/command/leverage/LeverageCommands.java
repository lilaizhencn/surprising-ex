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
            var work = new AccountLeverageChange(
                    owner.runtimeState(), owner.identities(), userId, command);
            owner.deferControl(() -> {
                if (!work.poll()) return false;
                if (owner.runtimeState().revision() != beforeRevision) owner.requestCommitPublication();
                return true;
            });
        } else if (DerivativeAccountCommandProcessor.updateLeverage(
                owner.runtimeState(), owner.identities(), userId, command)) {
            owner.requestCommitPublication();
        }
    }
}
