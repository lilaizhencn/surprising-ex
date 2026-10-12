package com.surprising.aeron.service.command.insurance;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.RuntimeInsuranceFundStateTransitions;

/** 保险基金调整命令。 */
public final class InsuranceFundCommands {
    private final CommandResultContext owner;

    public InsuranceFundCommands(CommandResultContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeAdjustInsuranceFund(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeAdjustInsuranceFund(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            owner.deferControl(RuntimeInsuranceFundStateTransitions.prepare(owner.reusableInsuranceAdjustment(),
                    owner.runtimeState(), owner.identities(), command));
        } else {
            RuntimeInsuranceFundStateTransitions.adjust(owner.runtimeState(), owner.identities(), command);
        }
        owner.requestCommitPublication();
    }
}
