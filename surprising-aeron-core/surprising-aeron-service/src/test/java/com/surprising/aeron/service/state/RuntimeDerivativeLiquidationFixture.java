package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.RuntimeDerivativeLiquidationProcessor.*;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.ExecuteAdlCommand;
import java.util.Collection;
import java.util.List;

/** Builds isolated runtime state for financial parity tests. */
final class RuntimeDerivativeLiquidationFixture {
    public static TradingRuntimeState simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        RuntimeIdentityRegistry identities) {
        return simulateExecution(before, command, List.of(), identities);
    }

    public static TradingRuntimeState simulateExecution(TradingCoreState before,
                                                        ExecuteLiquidationCommand command,
                                                        Collection<CoreOrderState> canceledOrders,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual liquidation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState simulateCancellationAdvance(TradingCoreState before,
                                                                  ExecuteLiquidationCommand command,
                                                                  Collection<CoreOrderState> canceledOrders,
                                                                  long nextCursorOrderId,
                                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || nextCursorOrderId <= 0
                || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation cancellation simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState simulateResolution(TradingCoreState before,
                                                         ResolveLiquidationCommand command,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyResolutionRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState simulateAdl(TradingCoreState before, ExecuteAdlCommand command,
                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyAdlRuntime(command, runtime, identities);
    }
}
