package com.surprising.aeron.service.command.adl;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;

/** 自动减仓（ADL）命令。 */
public final class AdlCommands {
    private final CommandResultContext owner;
    private RuntimeDerivativeLiquidationProcessor.AdlWork asyncWork;
    private final AdlContinuation continuation = new AdlContinuation();

    public AdlCommands(CommandResultContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeExecuteAdl(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
        owner.setSingleChangedUser(command.targetUserId());
        if (owner.asynchronousCommands()) {
            asyncWork = RuntimeDerivativeLiquidationProcessor.beginAdl(asyncWork,
                    command, owner.runtimeState(), owner.identities());
            continuation.prepare(asyncWork);
            owner.deferControl(continuation);
        } else {
            RuntimeDerivativeLiquidationProcessor.applyAdlRuntime(
                    command, owner.runtimeState(), owner.identities());
            owner.requestCommitPublication();
        }
    }

    /** Reusable owner callback for the slot-scoped ADL account work. */
    private final class AdlContinuation implements java.util.function.BooleanSupplier {
        private RuntimeDerivativeLiquidationProcessor.AdlWork work;

        void prepare(RuntimeDerivativeLiquidationProcessor.AdlWork work) { this.work = work; }

        @Override
        public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }
}
