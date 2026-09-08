package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.RuntimeDerivativeMatchProcessor.*;
import exchange.core2.core.common.MatcherResult.MatcherEvent;
import java.util.List;

/** Builds isolated runtime state for financial parity tests. */
final class RuntimeDerivativeMatchFixture {
    public static TradingRuntimeState simulate(TradingCoreState before, long takerOrderId,
                                               List<MatcherEvent> matches,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || matches == null || identities == null || !before.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid perpetual match simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyRuntime(takerOrderId, matches, runtime, identities);
    }

    /**
     * Executes the native fill and applies the reducer-owned user revision plan for an asynchronous match batch.
     * User revisions encode command-lifecycle transitions (including reservation release), which cannot be inferred
     * from the fill alone once the reservation was created by an earlier command.
     */
    public static TradingRuntimeState simulateTransition(TradingCoreState before, TradingCoreState expected,
                                                         long takerOrderId, List<MatcherEvent> matches,
                                                         RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || identities == null
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        return applyTransition(before, expected, takerOrderId, matches, runtime, identities);
    }

    public static TradingRuntimeState applyTransition(TradingCoreState before, TradingCoreState expected,
                                                       long takerOrderId, List<MatcherEvent> matches,
                                                       TradingRuntimeState runtime,
                                                       RuntimeIdentityRegistry identities) {
        if (before == null || expected == null || runtime == null || identities == null
                || !before.productLine().isDerivative()
                || expected.productLine() != before.productLine()) {
            throw new IllegalArgumentException("invalid perpetual match transition");
        }
        applyRuntime(takerOrderId, matches, runtime, identities);
        for (Long userId : expected.changedUserIds()) {
            CoreUserState planned = expected.users().get(userId);
            if (planned == null) {
                throw new IllegalStateException("runtime match changed user is missing: " + userId);
            }
            UserRuntime actual = runtime.user(userId);
            if (actual == null || actual.productLine() != planned.productLine()) {
                throw new IllegalStateException("runtime match user is missing: " + userId);
            }
            if (actual.revision() != planned.revision()) {
                runtime.putUser(new UserRuntime(actual.productLine(), userId, planned.revision(),
                        actual.positionMode()));
            }
        }
        runtime.setMetadata(expected.productLine(), expected.revision());
        return runtime;
    }
}
