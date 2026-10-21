package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;

import static com.surprising.aeron.service.state.risk.RuntimeDerivativeRiskProcessor.*;

/** Builds isolated runtime state for financial parity tests. */
public final class RuntimeDerivativeRiskFixture {
    public static TradingRuntimeState simulateMarkPrice(TradingCoreState before, ApplyMarkPriceCommand command,
                                                        Iterable<Long> indexedUserIds,
                                                        RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        applyMarkPriceRuntime(command, runtime, identities);
        if (runtime.riskScanControl().enabled()) {
            applyContinuationRuntime(runtime.riskScanControl().scanBatchSize(), indexedUserIds, runtime, identities);
        }
        return runtime;
    }

    public static TradingRuntimeState simulateContinuation(TradingCoreState before, int maxWork,
                                                           Iterable<Long> indexedUserIds,
                                                           RuntimeIdentityRegistry identities) {
        if (before == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual risk continuation");
        }
        if (maxWork <= 0 || maxWork > 4096) throw new IllegalArgumentException("invalid risk scan batch size");
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        applyContinuationRuntime(maxWork, indexedUserIds, runtime, identities);
        return runtime;
    }
}
