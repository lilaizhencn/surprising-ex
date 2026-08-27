package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.AccountLaneSnapshot;
import com.surprising.aeron.service.state.CoreFeePolicyState;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TransferRuntime;
import com.surprising.product.api.ProductLine;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record CoreSnapshotImage(
        ProductLine productLine,
        long appliedCommandCount,
        long probeValue,
        long sourceSequenceDigest,
        long snapshotId,
        long coreSequence,
        long clusterTimestamp,
        long clusterPosition,
        MatcherSnapshot matcherSnapshot,
        TradingCoreState tradingState,
        Map<CoreProbeState.SourceKey, Long> sourceSequences,
        Map<UUID, CoreProbeState.StoredResult> commandResults,
        CoreExportState.Snapshot exportState,
        Map<Long, CoreFeePolicyState> feePolicies,
        Map<Long, TransferRuntime> pendingTransfers,
        TerminalStateRetention terminalRetention,
        List<AccountLaneSnapshot> accountLanes) {

    CoreSnapshotImage {
        sourceSequences = Collections.unmodifiableMap(new LinkedHashMap<>(sourceSequences));
        commandResults = Collections.unmodifiableMap(new LinkedHashMap<>(commandResults));
        feePolicies = Collections.unmodifiableMap(new LinkedHashMap<>(feePolicies));
        pendingTransfers = Collections.unmodifiableMap(new LinkedHashMap<>(pendingTransfers));
        accountLanes = List.copyOf(accountLanes);
        if (productLine == null || appliedCommandCount < 0 || snapshotId <= 0
                || coreSequence != appliedCommandCount || clusterTimestamp < 0 || clusterPosition < 0
                || matcherSnapshot == null || tradingState == null || exportState == null
                || terminalRetention == null || tradingState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid core snapshot image");
        }
    }
}
