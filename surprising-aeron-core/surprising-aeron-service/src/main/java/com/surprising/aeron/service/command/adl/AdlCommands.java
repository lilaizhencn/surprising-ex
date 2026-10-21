package com.surprising.aeron.service.command.adl;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.state.adl.RuntimeAdlExecution;

/** 自动减仓（ADL）命令。 */
public final class AdlCommands {
    private final TradingCoreRuntime owner;

    public AdlCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeExecuteAdl(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.resultBuilder.setSingleChangedUser(command.targetUserId());
        if (owner.asynchronousCommands()) {
            var work = RuntimeAdlExecution.begin(owner.directCommand.reusableAdlWork(),
                    command, owner.runtimeState(), owner.identities());
            owner.directCommand.deferAdlControl(owner, work);
        } else {
            RuntimeAdlExecution.applyRuntime(
                    command, owner.runtimeState(), owner.identities());
            owner.requestCommitPublication();
        }
    }

}
