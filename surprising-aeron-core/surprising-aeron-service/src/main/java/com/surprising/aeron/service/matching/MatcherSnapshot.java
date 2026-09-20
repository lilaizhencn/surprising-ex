package com.surprising.aeron.service.matching;

import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor.SerializedModule;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record MatcherSnapshot(
        ProductLine productLine,
        LaneTopology topology,
        long snapshotId,
        long coreSequence,
        long matcherSequence,
        List<MatcherShardProgress> matcherShardProgress,
        long coreBusinessStateHash,
        int engineStateHash,
        Map<String, Integer> symbols,
        Set<Long> users,
        List<SerializedModule> modules) {

    public MatcherSnapshot {
        if (productLine == null || topology == null
                || snapshotId <= 0 || coreSequence < 0
                || matcherSequence < 0 || matcherShardProgress == null || symbols == null || users == null
                || modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("invalid matcher snapshot manifest");
        }
        symbols = Collections.unmodifiableMap(new TreeMap<>(symbols));
        users = Collections.unmodifiableSet(new TreeSet<>(users));
        matcherShardProgress = List.copyOf(matcherShardProgress);
        modules = List.copyOf(modules);
        if (symbols.values().stream().anyMatch(symbolId -> symbolId == null || symbolId <= 0)
                || new java.util.HashSet<>(symbols.values()).size() != symbols.size()
                || users.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new IllegalArgumentException("invalid matcher registries");
        }
        if (matcherShardProgress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        boolean[] progress = new boolean[matcherShardProgress.size()];
        for (int progressIndex = 0; progressIndex < matcherShardProgress.size(); progressIndex++) {
            MatcherShardProgress shard = matcherShardProgress.get(progressIndex);
            int index = shard.matcherShardId() + 1;
            if (index < 0 || index >= progress.length || progress[index]
                    || index != progressIndex) {
                throw new IllegalArgumentException("invalid matcher shard progress");
            }
            progress[index] = true;
        }
        for (boolean present : progress) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        boolean[] matching = new boolean[topology.matchingEngineCount()];
        boolean[] risk = new boolean[topology.riskEngineCount()];
        long maximumModuleSequence = Long.MIN_VALUE;
        for (SerializedModule module : modules) {
            if (module.snapshotId() != snapshotId || module.sequence() < 0 || module.sequence() > matcherSequence
                    || module.instanceId() < 0) {
                throw new IllegalArgumentException("matcher snapshot module watermark mismatch");
            }
            maximumModuleSequence = Math.max(maximumModuleSequence, module.sequence());
            if (module.type() == SerializedModuleType.MATCHING_ENGINE_ROUTER) {
                if (module.instanceId() >= matching.length || matching[module.instanceId()]) {
                    throw new IllegalArgumentException("duplicate or out-of-range matching module");
                }
                matching[module.instanceId()] = true;
            } else if (module.type() == SerializedModuleType.RISK_ENGINE) {
                if (module.instanceId() >= risk.length || risk[module.instanceId()]) {
                    throw new IllegalArgumentException("duplicate or out-of-range risk module");
                }
                risk[module.instanceId()] = true;
            } else {
                throw new IllegalArgumentException("unsupported matcher module");
            }
        }
        boolean completeMatching = true;
        for (boolean present : matching) completeMatching &= present;
        boolean completeRisk = true;
        for (boolean present : risk) completeRisk &= present;
        if (!completeMatching || !completeRisk
                || modules.size() != topology.matchingEngineCount() + topology.riskEngineCount()
                || maximumModuleSequence != matcherSequence) {
            throw new IllegalArgumentException("incomplete matcher snapshot modules");
        }
    }

    public int matchingEngineCount() { return topology.matchingEngineCount(); }
    public int riskEngineCount() { return topology.riskEngineCount(); }
    public int matcherShardMask() { return topology.matcherShardMask(); }
    public int accountLaneCount() { return topology.accountLaneCount(); }
    public long accountLaneSeed() { return topology.accountLaneSeed(); }

    public MatcherShardProgress progress(int matcherShardId) {
        int index = matcherShardId + 1;
        if (index < 0 || index >= matcherShardProgress.size()) {
            throw new IllegalArgumentException("matcher shard is outside snapshot topology");
        }
        MatcherShardProgress progress = matcherShardProgress.get(index);
        if (progress.matcherShardId() != matcherShardId) {
            throw new IllegalStateException("matcher shard progress is not canonically ordered");
        }
        return progress;
    }

    public void verifyCoreState(TradingCoreState state, long expectedCoreSequence,
                                long expectedCoreBusinessStateHash) {
        if (state == null) throw new IllegalStateException("Core snapshot state is missing");
        verifyCoreManifest(state.productLine(), expectedCoreSequence, expectedCoreBusinessStateHash);
    }

    public void verifyCoreManifest(ProductLine expectedProductLine, long expectedCoreSequence,
                                   long expectedBusinessStateHash) {
        if (expectedProductLine != productLine || expectedCoreSequence != coreSequence
                || expectedBusinessStateHash != coreBusinessStateHash) {
            throw new IllegalStateException("Core and matcher snapshot manifests do not match"
                    + " (productLine=" + productLine + '/' + expectedProductLine
                    + ", coreSequence=" + coreSequence + '/' + expectedCoreSequence
                    + ", businessStateHash=" + coreBusinessStateHash + '/'
                    + expectedBusinessStateHash + ')');
        }
    }

}
