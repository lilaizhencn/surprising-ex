package com.surprising.aeron.service.state;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

final class LedgerControlTestSupport {
    static void await(TradingRuntimeState runtime, BooleanSupplier completion) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!completion.getAsBoolean()) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger control completion timed out");
            Thread.onSpinWait();
        }
    }

    static TradingCoreState commit(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean external) {
        RuntimeFundsAccumulator funds = new RuntimeFundsAccumulator();
        runtime.appendFundsDelta(funds);
        funds.requireConserved(external);
        runtime.clearChangedKeys();
        return RuntimeStateMaterializer.materialize(runtime, identities);
    }
}
