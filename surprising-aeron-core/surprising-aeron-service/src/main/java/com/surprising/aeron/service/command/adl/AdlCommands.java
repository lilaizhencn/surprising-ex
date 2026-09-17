package com.surprising.aeron.service.command.adl;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;

/** 自动减仓（ADL）命令。 */
public final class AdlCommands {
    private final CommandResultContext owner;

    public AdlCommands(CommandResultContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeExecuteAdl(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.setSingleChangedUser(command.targetUserId());
        if (owner.asynchronousCommands()) {
            var work = RuntimeDerivativeLiquidationProcessor.beginAdl(owner.reusableAdlWork(),
                    command, owner.runtimeState(), owner.identities());
            owner.deferAdlControl(work);
        } else {
            RuntimeDerivativeLiquidationProcessor.applyAdlRuntime(
                    command, owner.runtimeState(), owner.identities());
            owner.requestCommitPublication();
        }
    }

}
