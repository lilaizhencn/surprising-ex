package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.snapshot.RuntimeStateProjector;
import java.util.UUID;

import static com.surprising.aeron.service.state.lifecycle.RuntimePerpetualFundingProcessor.*;

/** Builds isolated runtime state for financial parity tests. */
public final class RuntimePerpetualFundingFixture {
    public static FundingResult simulate(TradingCoreState before, ApplyFundingCommand command,
                                         Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                         RuntimeIdentityRegistry identities) {
        if (before == null || command == null || identities == null) {
            throw new IllegalArgumentException("invalid perpetual funding simulation");
        }
        TradingRuntimeState runtime = RuntimeStateProjector.project(before, identities);
        Iterable<Long> users = indexedUserIds == null ? before.users().keySet() : indexedUserIds;
        return applyRuntime(command, users, chunkCommandId, runtime, identities);
    }
}
