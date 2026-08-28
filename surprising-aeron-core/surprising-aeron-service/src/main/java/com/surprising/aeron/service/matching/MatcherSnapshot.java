package com.surprising.aeron.service.matching;

import com.surprising.aeron.service.state.CoreOrderStatus;
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
        String coreShardId,
        int routeVersion,
        LaneTopology topology,
        long snapshotId,
        long coreSequence,
        long matcherSequence,
        long matcherPrefixDigest,
        long coreBusinessStateHash,
        int engineStateHash,
        int bookStateHash,
        long symbolRegistryHash,
        long symbolRouteHash,
        long userRegistryHash,
        long instrumentRegistryHash,
        long activeOrderHash,
        String forkGitSha,
        String artifactSha256,
        long matcherConfigHash,
        Map<String, Integer> symbols,
        Set<Long> users,
        List<SerializedModule> modules) {

    public static final String CORE_SHARD_ID = "default";
    public static final int ROUTE_VERSION = LaneTopology.ROUTE_VERSION;
    public static final String FORK_GIT_SHA = "4c4d163b6ba736a43360b325cdd7b9fb8c20648d";
    public static final String ARTIFACT_SHA256 =
            "d4ab72853924edc32069ab7158e7bcc5d374ecc1bcd594df04128ab459732b86";
    public static final long MATCHER_CONFIG_HASH = matcherConfigHash(new LaneTopology(
            ROUTE_VERSION, LaneTopology.DEFAULT_MATCHING_ENGINE_COUNT,
            LaneTopology.DEFAULT_RISK_ENGINE_COUNT, LaneTopology.DEFAULT_MATCHING_ENGINE_COUNT - 1,
            LaneTopology.DEFAULT_ACCOUNT_LANE_COUNT,
            LaneTopology.DEFAULT_ACCOUNT_LANE_SEED, LaneTopology.DEFAULT_MATCHER_WINDOW_SIZE,
            LaneTopology.DEFAULT_QUEUE_CAPACITY, LaneTopology.DEFAULT_QUEUE_CAPACITY));

    public MatcherSnapshot {
        if (productLine == null || !CORE_SHARD_ID.equals(coreShardId)
                || routeVersion != ROUTE_VERSION || topology == null || topology.routeVersion() != routeVersion
                || snapshotId <= 0 || coreSequence < 0
                || matcherSequence < 0 || matcherPrefixDigest == 0 || !FORK_GIT_SHA.equals(forkGitSha)
                || !ARTIFACT_SHA256.equals(artifactSha256)
                || matcherConfigHash != matcherConfigHash(topology) || symbols == null || users == null
                || modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("invalid matcher snapshot manifest");
        }
        symbols = Collections.unmodifiableMap(new TreeMap<>(symbols));
        users = Collections.unmodifiableSet(new TreeSet<>(users));
        modules = List.copyOf(modules);
        if (symbols.values().stream().anyMatch(symbolId -> symbolId == null || symbolId <= 0)
                || new java.util.HashSet<>(symbols.values()).size() != symbols.size()
                || users.stream().anyMatch(userId -> userId == null || userId <= 0)) {
            throw new IllegalArgumentException("invalid matcher registries");
        }
        if (symbolRegistryHash != symbolRegistryHash(symbols)
                || symbolRouteHash != topology.symbolRouteHash(symbols)
                || userRegistryHash != userRegistryHash(users)) {
            throw new IllegalArgumentException("matcher registry hash mismatch");
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
    public long topologyHash() { return topology.topologyHash(); }

    public static long matcherConfigHash(LaneTopology topology) {
        return hashText("matching=" + topology.matchingEngineCount()
                + ";risk=" + topology.riskEngineCount()
                + ";wait=RUNTIME_CONFIGURED"
                + ";riskMode=MATCHING_ONLY;margin=DISABLED;eventsPooling=true"
                + ";matcherWindow=" + topology.matcherWindowSize()
                + ";completionCapacity=" + topology.matchingCompletionCapacity()
                + ";accountLanes=" + topology.accountLaneCount()
                + ";accountLaneSeed=" + topology.accountLaneSeed()
                + ";accountLaneQueue=" + topology.accountLaneQueueCapacity());
    }

    public void verifyCoreState(TradingCoreState state, long expectedCoreSequence) {
        if (state == null) throw new IllegalStateException("Core snapshot state is missing");
        long actualBusinessStateHash = state.businessStateHash();
        long actualInstrumentRegistryHash = instrumentRegistryHash(state);
        long actualActiveOrderHash = activeOrderHash(state);
        verifyCoreManifest(state.productLine(), expectedCoreSequence, actualBusinessStateHash);
        if (instrumentRegistryHash != actualInstrumentRegistryHash
                || activeOrderHash != actualActiveOrderHash) {
            throw new IllegalStateException("Core and matcher snapshot manifests do not match"
                    + " (productLine=" + productLine + '/' + state.productLine()
                    + ", coreSequence=" + coreSequence + '/' + expectedCoreSequence
                    + ", businessStateHash=" + coreBusinessStateHash + '/' + actualBusinessStateHash
                    + ", instrumentRegistryHash=" + instrumentRegistryHash + '/'
                    + actualInstrumentRegistryHash + ", activeOrderHash=" + activeOrderHash + '/'
                    + actualActiveOrderHash + ')');
        }
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

    public static long symbolRegistryHash(Map<String, Integer> symbols) {
        long hash = offset();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(symbols).entrySet()) {
            hash = mix(hash, entry.getKey());
            hash = mix(hash, entry.getValue());
        }
        return hash;
    }

    public static long userRegistryHash(Set<Long> users) {
        long hash = offset();
        for (Long userId : new TreeSet<>(users)) hash = mix(hash, userId);
        return hash;
    }

    public static long instrumentRegistryHash(TradingCoreState state) {
        long hash = offset();
        for (var instrument : state.instruments().values()) {
            hash = mix(hash, instrument.symbol());
            hash = mix(hash, instrument.version());
        }
        return hash;
    }

    public static long activeOrderHash(TradingCoreState state) {
        long hash = offset();
        for (var order : state.orders().values()) {
            if (order.status() != CoreOrderStatus.OPEN) continue;
            hash = mix(hash, order.orderId());
            hash = mix(hash, order.userId());
            hash = mix(hash, order.symbol());
            hash = mix(hash, order.side().wireCode());
            hash = mix(hash, order.priceTicks());
            hash = mix(hash, order.remainingQuantitySteps());
        }
        return hash;
    }

    private static long hashText(String value) {
        return mix(offset(), value);
    }

    private static long offset() {
        return 0xcbf29ce484222325L;
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xff;
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }

    private static long mix(long hash, String value) {
        long mixed = hash;
        for (int index = 0; index < value.length(); index++) {
            mixed ^= value.charAt(index);
            mixed *= 0x100000001b3L;
        }
        return mixed;
    }
}
