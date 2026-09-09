package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TradingCoreRuntimeRestoreTestSupport {

    private TradingCoreRuntimeRestoreTestSupport() {
    }

    static TradingCoreRuntime restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CommandResultLedger.StoredResult> commandResults,
            Map<TradingCoreRuntime.SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState) {
        if (!snapshotState.orders().isEmpty()) {
            throw new IllegalArgumentException("matcher snapshot is required for restored open orders");
        }
        MatcherSnapshot matcherSnapshot;
        try (DeterministicExchangeCoreAdapter adapter = new DeterministicExchangeCoreAdapter()) {
            matcherSnapshot = adapter.snapshotAsync(1, appliedCommandCount,
                    snapshotState.businessStateHash(), snapshotState, List.of()).join();
        }
        return restore(productLine, appliedCommandCount, probeValue, commandResults, lastSourceSequences,
                snapshotState, exportState, matcherSnapshot);
    }

    static TradingCoreRuntime restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CommandResultLedger.StoredResult> commandResults,
            Map<TradingCoreRuntime.SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot) {
        TradingCoreRuntime candidate = TradingCoreRuntime.prepareRestore(productLine, appliedCommandCount, probeValue,
                commandResults, lastSourceSequences, snapshotState, exportState, new TerminalStateRetention(),
                matcherSnapshot, appliedCommandCount, Map.of(), Map.of(), 0, 0);
        try {
            long projectionSequence = candidate.snapshotProjectionSequence();
            var accountLanes = candidate.accountLaneSnapshots(projectionSequence, snapshotState);
            candidate.restoreAccountLaneSnapshots(accountLanes, projectionSequence);
            candidate.activate();
            return candidate;
        } catch (RuntimeException failure) {
            candidate.close();
            throw failure;
        }
    }
}
