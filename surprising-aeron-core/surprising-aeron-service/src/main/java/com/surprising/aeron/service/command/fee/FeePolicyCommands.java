package com.surprising.aeron.service.command.fee;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;

/** 费率策略配置命令。 */
public final class FeePolicyCommands implements java.util.function.IntFunction<Object>, java.util.function.BooleanSupplier {
    private final FeePolicyCommandContext owner;
    private com.surprising.aeron.protocol.UpsertFeePolicyCommand pending;

    public FeePolicyCommands(FeePolicyCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeUpsertFeePolicy(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeUpsertFeePolicy(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            if (pending != null) throw new IllegalStateException("fee policy command is still pending");
            pending = command;
            try { owner.runtimeState().dispatchControlLanes(1L, this); }
            catch (RuntimeException | Error failure) { pending = null; throw failure; }
            owner.deferControl(this);
        } else {
            owner.runtimeState().upsertFeePolicy(command);
            owner.requestCommitPublication();
        }
    }

    @Override public Object apply(int laneId) {
        if (laneId != 0 || pending == null) throw new IllegalStateException("fee policy reached the wrong configuration Lane");
        owner.runtimeState().upsertFeePolicy(pending);
        return null;
    }

    @Override public boolean getAsBoolean() {
        try {
            if (!owner.runtimeState().pollControlLanes()) return false;
        } catch (RuntimeException | Error failure) { pending = null; throw failure; }
        pending = null;
        owner.requestCommitPublication();
        return true;
    }
}
