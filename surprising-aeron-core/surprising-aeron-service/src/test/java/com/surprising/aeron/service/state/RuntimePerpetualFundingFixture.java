package com.surprising.aeron.service.state;

import static com.surprising.aeron.service.state.RuntimePerpetualFundingProcessor.*;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import java.util.UUID;

/** Builds isolated runtime state for financial parity tests. */
final class RuntimePerpetualFundingFixture {
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
