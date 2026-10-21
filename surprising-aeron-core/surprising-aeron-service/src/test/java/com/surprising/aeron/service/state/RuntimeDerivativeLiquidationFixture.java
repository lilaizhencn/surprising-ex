package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ExecuteAdlCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.order.RuntimeTestStateTransitions;
import com.surprising.aeron.service.state.risk.RuntimeAdlExecution;
import com.surprising.aeron.service.state.risk.RuntimeLiquidationResolution;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import java.util.Collection;
import java.util.List;

import static com.surprising.aeron.service.state.risk.RuntimeDerivativeLiquidationProcessor.*;

/** Builds isolated runtime state for financial parity tests. */
public final class RuntimeDerivativeLiquidationFixture {
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
        return applyExecutionRuntime(command, RuntimeTestStateTransitions.cancellationRoutes(canceledOrders), runtime, identities);
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
        return applyCancellationAdvanceRuntime(command, RuntimeTestStateTransitions.cancellationRoutes(canceledOrders), nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState simulateResolution(TradingCoreState before,
                                                         ResolveLiquidationCommand command,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return RuntimeLiquidationResolution.applyRuntime(command, runtime, identities,
                before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState simulateAdl(TradingCoreState before, ExecuteAdlCommand command,
                                                  RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return RuntimeAdlExecution.applyRuntime(command, runtime, identities);
    }
}
