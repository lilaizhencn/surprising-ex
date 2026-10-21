package com.surprising.aeron.service.command.liquidation;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.aeron.service.state.liquidation.RuntimeDerivativeLiquidationProcessor;
import com.surprising.aeron.service.state.liquidation.RuntimeLiquidationResolution;
import java.util.Collection;

/**
 * 强平业务：交易编排完成状态/价格/游标检查和 Matcher 撤单后，在所属 Lane 处理持仓与资金。
 * 分页撤单推进与 RESOLVE_LIQUIDATION 结果确认各保留原命令边界，详见模块 README。
 */
public final class LiquidationCommands {
    private final TradingCoreRuntime owner;

    public LiquidationCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void executeResolveLiquidation(CoreMessage message, long clusterTimestamp) {
        var command = TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
        if (owner.asynchronousCommands()) {
            var work = RuntimeLiquidationResolution.begin(owner.directCommand.reusableLiquidationResolutionWork(), command,
                    owner.runtimeState(), owner.identities(), owner.activeLiquidationIds());
            owner.directCommand.deferLiquidationResolutionControl(owner, work);
        } else {
            RuntimeLiquidationResolution.applyRuntime(command, owner.runtimeState(),
                    owner.identities(), owner.activeLiquidationIds());
            owner.requestCommitPublication();
        }
    }

    public void executeLiquidationRuntime(ExecuteLiquidationCommand command,
                                          Collection<CancellationOrder> canceledOrders) {
        RuntimeDerivativeLiquidationProcessor.applyExecutionRuntime(command, canceledOrders,
                owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

    public void advanceLiquidationCancellationRuntime(ExecuteLiquidationCommand command,
                                                       Collection<CancellationOrder> canceledOrders,
                                                       long nextCursorOrderId) {
        RuntimeDerivativeLiquidationProcessor.applyCancellationAdvanceRuntime(command, canceledOrders,
                nextCursorOrderId, owner.runtimeState(), owner.identities());
        owner.requestCommitPublication();
    }

}
