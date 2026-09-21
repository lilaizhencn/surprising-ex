package com.surprising.aeron.service.command.fee;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;

/** 费率策略配置命令。 */
public final class FeePolicyCommands {
    private final FeePolicyCommandContext owner;

    public FeePolicyCommands(FeePolicyCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeUpsertFeePolicy(CoreMessage message, long clusterTimestamp) {
        owner.runtimeState().upsertFeePolicy(
                TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }
}
