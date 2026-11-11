package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor.SerializedModule;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 原生订单簿快照清单，配对产品线、拓扑、Core 水位与业务哈希、引擎哈希、注册表和序列化模块。账户和原生订单簿的逐项核对仍由恢复引擎负责。 */
public record OrderBookSnapshot(
        ProductLine productLine,
        LaneTopology topology,
        long snapshotId,
        long coreSequence,
        long matcherSequence,
        List<ShardProgress> matcherShardProgress,
        long coreBusinessStateHash,
        int engineStateHash,
        Map<String, Integer> symbols,
        Set<Long> users,
        List<SerializedModule> modules) {

    /** 保存一份订单簿分片的执行进度，把分片身份和已经到达的撮合序号放在一起。 */
    public record ShardProgress(int matcherShardId, long matcherSequence) {

        /** 拒绝超出编号下界的分片和负撮合序号，避免携带无效游标参与提交或恢复检查。 */
        public ShardProgress {
            if (matcherShardId < -1 || matcherSequence < 0) {
                throw new IllegalArgumentException("invalid matcher shard progress");
            }
        }
    }

    /** 分片进度必须按负一到最后撮合分片的完整顺序。每类引擎模块实例必须齐全、唯一且编号有效，各模块序号在零到 matcherSequence 之间，并要求最大模块序号恰好等于该水位。 */
    public OrderBookSnapshot {
        if (productLine == null || topology == null
                || snapshotId <= 0 || coreSequence < 0
                || matcherSequence < 0 || matcherShardProgress == null || symbols == null || users == null
                || modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("invalid matcher snapshot manifest");
        }
        symbols = Collections.unmodifiableMap(symbols instanceof java.util.NavigableMap<?, ?> ? symbols : new TreeMap<>(symbols));
        users = Collections.unmodifiableSet(users instanceof java.util.NavigableSet<?> ? users : new TreeSet<>(users));
        matcherShardProgress = List.copyOf(matcherShardProgress);
        modules = List.copyOf(modules);
        java.util.HashSet<Integer> uniqueSymbolIds = new java.util.HashSet<>(symbols.size());
        for (Integer symbolId : symbols.values()) {
            if (symbolId == null || symbolId <= 0 || !uniqueSymbolIds.add(symbolId)) {
                throw new IllegalArgumentException("invalid matcher registries");
            }
        }
        for (Long userId : users) {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("invalid matcher registries");
            }
        }
        if (matcherShardProgress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        for (int progressIndex = 0; progressIndex < matcherShardProgress.size(); progressIndex++) {
            if (matcherShardProgress.get(progressIndex).matcherShardId() != progressIndex - 1) {
                throw new IllegalArgumentException("invalid matcher shard progress");
            }
        }
        boolean[] matching = new boolean[topology.matchingEngineCount()];
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

            } else {
                throw new IllegalArgumentException("unsupported matcher module");
            }
        }
        if (modules.size() != topology.matchingEngineCount()
                || maximumModuleSequence != matcherSequence) {
            throw new IllegalArgumentException("incomplete matcher snapshot modules");
        }
    }

    /** 逐项要求产品线、Core 序号和业务哈希与清单相同。不在此重算引擎哈希、核对模块内容或执行恢复。 */
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
