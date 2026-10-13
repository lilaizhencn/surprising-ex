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

    static void awaitFunds(TradingRuntimeState runtime, boolean external) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!runtime.pollControlFundsValidation(external)) {
            runtime.assertAccountLanesHealthy();
            if (System.nanoTime() >= deadline) throw new AssertionError("Ledger funds validation timed out");
            Thread.onSpinWait();
        }
    }

    static TradingCoreState commit(TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean external) {
        awaitFunds(runtime, external);
        runtime.clearChangedKeys();
        boolean acquired = runtime.accountLanesStarted && !runtime.ownerLaneAccess;
        try {
            if (acquired) while (!runtime.tryAcquireOwnerLaneAccess()) Thread.onSpinWait();
            return RuntimeStateMaterializer.materialize(runtime, identities);
        } finally { if (acquired) runtime.releaseOwnerLaneAccess(); }
    }
}
