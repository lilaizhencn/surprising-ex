package com.surprising.aeron.service.command.liquidation;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.model.CoreOrderState;
import java.util.Collection;

/** 衍生品强平执行、撤单推进和结果确认命令。 */
public final class LiquidationCommands {
    private final LiquidationCommandContext owner;
    private RuntimeDerivativeLiquidationProcessor.ResolutionWork asyncWork;
    private final ResolutionContinuation continuation = new ResolutionContinuation();

    public LiquidationCommands(LiquidationCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeResolveLiquidation(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            asyncWork = RuntimeDerivativeLiquidationProcessor.beginResolution(asyncWork, command,
                    owner.runtimeState(), owner.identities(), owner.activeLiquidationIds());
            continuation.prepare(asyncWork);
            owner.deferControl(continuation);
        } else {
            RuntimeDerivativeLiquidationProcessor.applyResolutionRuntime(command, owner.runtimeState(),
                    owner.identities(), owner.activeLiquidationIds());
            owner.requestCommitPublication();
        }
    }

    public void executeLiquidationRuntime(ExecuteLiquidationCommand command,
                                          Collection<CoreOrderState> canceledOrders) {
        RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(command, canceledOrders,
                owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

    public void advanceLiquidationCancellationRuntime(ExecuteLiquidationCommand command,
                                                       Collection<CoreOrderState> canceledOrders,
                                                       long nextCursorOrderId) {
        RuntimeDerivativeLiquidationProcessor.applyCancellationAdvanceRuntime(command, canceledOrders,
                nextCursorOrderId, owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

    /** Reusable owner callback for the slot-scoped liquidation resolution work. */
    private final class ResolutionContinuation implements java.util.function.BooleanSupplier {
        private RuntimeDerivativeLiquidationProcessor.ResolutionWork work;

        void prepare(RuntimeDerivativeLiquidationProcessor.ResolutionWork work) { this.work = work; }

        @Override
        public boolean getAsBoolean() {
            if (!work.getAsBoolean()) return false;
            owner.requestCommitPublication();
            return true;
        }
    }
}
