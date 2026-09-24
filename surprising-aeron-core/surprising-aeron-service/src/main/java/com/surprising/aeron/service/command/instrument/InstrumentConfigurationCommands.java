package com.surprising.aeron.service.command.instrument;

import com.surprising.aeron.service.command.CommandOwnerContext;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimeInstrumentStateTransitions;

/** 币对配置与维护状态命令；六产品线按所属运行时隔离。 */
public final class InstrumentConfigurationCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    private final CommandOwnerContext owner;

    public InstrumentConfigurationCommands(CommandOwnerContext owner) { this.owner = java.util.Objects.requireNonNull(owner); }

    public void executeRegisterInstrument(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeRegisterInstrument(message.payloadUnsafe());
        RuntimeInstrumentStateTransitions.applyConfiguration(
                owner.runtimeState(), owner.identities(), command);
        owner.requestCommitPublication();
    }

    public void executeUpdateInstrumentMaintenance(CoreMessage message, long clusterTimestamp) {
        RuntimeInstrumentStateTransitions.updateMaintenance(owner.runtimeState(), owner.identities(),
                com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }
}
