package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CoreProbeStateRestoreTestSupport {

    private CoreProbeStateRestoreTestSupport() {
    }

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CoreProbeState.StoredResult> commandResults,
            Map<CoreProbeState.SourceKey, Long> lastSourceSequences,
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

    static CoreProbeState restore(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CoreProbeState.StoredResult> commandResults,
            Map<CoreProbeState.SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot) {
        CoreProbeState candidate = CoreProbeState.prepareRestore(productLine, appliedCommandCount, probeValue,
                commandResults, lastSourceSequences, snapshotState, exportState, new TerminalStateRetention(),
                matcherSnapshot, 0, Map.of(), Map.of());
        try {
            var accountLanes = candidate.accountLaneSnapshots(appliedCommandCount, snapshotState);
            candidate.restoreAccountLaneSnapshots(accountLanes, appliedCommandCount);
            candidate.activate();
            return candidate;
        } catch (RuntimeException failure) {
            candidate.close();
            throw failure;
        }
    }
}
