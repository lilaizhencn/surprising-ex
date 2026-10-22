package com.surprising.aeron.service.matcher;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.product.api.ProductLine;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor.SerializedModule;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 原生 Matcher 快照清单，配对产品线、拓扑、Core 水位与业务哈希、引擎哈希、注册表和序列化模块。构造时检查模块齐全及其序号边界，不包含另建的可执行订单簿；账户和原生订单簿的逐项核对仍由恢复适配器负责。已有有序注册表会被借用再只读包装，模块和进度只复制外层列表。
 * @param productLine 该原生快照所属的独立产品线
 * @param topology 保存引擎和账户 Lane 数量及队列路由设置的拓扑
 * @param snapshotId 本次所有原生模块共同使用的正快照编号
 * @param coreSequence 与本清单配对的 Core 命令水位
 * @param matcherSequence 原生模块的共同快照上界，最大模块序号必须达到此值
 * @param matcherShardProgress 按负一控制分片、零到最后撮合分片排列的完整业务证据进度
 * @param coreBusinessStateHash 配对 Core 状态的业务哈希，构造时只保存、不重新计算
 * @param engineStateHash 原生引擎状态哈希，构造时只保存、不重新计算
 * @param symbols 币对名字到正且唯一的引擎符号编号表，已有 NavigableMap 会借用，其他 Map 转 TreeMap
 * @param users 原生已注册正数用户集合，已有 NavigableSet 会借用，其他 Set 转 TreeSet
 * @param modules 本次所有撮合和风险引擎序列化模块，复制列表而不在此深拷贝每个模块载荷
 */
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

    /**
     * 校验基础对象及正快照编号，固定外层列表，检查注册编号为正且唯一、用户为正。分片进度必须按负一到最后撮合分片的完整顺序；每类引擎模块实例必须齐全、唯一且编号有效，各模块序号在零到 matcherSequence 之间，并要求最大模块序号恰好等于该水位。这里不重新计算业务/引擎哈希或规范化币对名字。
     */
    public MatcherSnapshot {
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
        // 本次构造检查过的引擎符号编号集合，防止两个币对指向同一编号。
        java.util.HashSet<Integer> uniqueSymbolIds = new java.util.HashSet<>(symbols.size());
        for (Integer /* 当前注册表条目的内部引擎编号，要求正数且唯一。 */ symbolId : symbols.values()) {
            if (symbolId == null || symbolId <= 0 || !uniqueSymbolIds.add(symbolId)) {
                throw new IllegalArgumentException("invalid matcher registries");
            }
        }
        for (Long /* 当前原生用户编号，要求非空正数。 */ userId : users) {
            if (userId == null || userId <= 0) {
                throw new IllegalArgumentException("invalid matcher registries");
            }
        }
        if (matcherShardProgress.size() != topology.matchingEngineCount() + 1) {
            throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        // 构造时各进度位置的存在位；progress 方法内则是取出的指定分片进度。
        boolean[] progress = new boolean[matcherShardProgress.size()];
        for (/* 当前进度条目在规定有序列表中的位置。 */ int progressIndex = 0; progressIndex < matcherShardProgress.size(); progressIndex++) {
            // 当前需要核对编号及位置的业务证据进度。
            MatcherShardProgress shard = matcherShardProgress.get(progressIndex);
            // 分片编号加一后的规定位置，控制负一对应零。
            int index = shard.matcherShardId() + 1;
            if (index < 0 || index >= progress.length || progress[index]
                    || index != progressIndex) {
                throw new IllegalArgumentException("invalid matcher shard progress");
            }
            progress[index] = true;
        }
        for (boolean /* 该分片或引擎实例是否已经在清单中出现。 */ present : progress) {
            if (!present) throw new IllegalArgumentException("incomplete matcher shard progress");
        }
        // 按撮合引擎实例编号保存的模块存在标记。
        boolean[] matching = new boolean[topology.matchingEngineCount()];
        // 按风险引擎实例编号保存的模块存在标记。
        boolean[] risk = new boolean[topology.riskEngineCount()];
        // 当前所有模块序号中的最大值，结束时必须等于清单 Matcher 水位。
        long maximumModuleSequence = Long.MIN_VALUE;
        for (SerializedModule /* 当前需要核对快照编号、实例类型和序号边界的原生模块。 */ module : modules) {
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
        // 所有撮合引擎实例是否都存在。
        boolean completeMatching = true;
        for (boolean /* 该分片或引擎实例是否已经在清单中出现。 */ present : matching) completeMatching &= present;
        // 所有风险引擎实例是否都存在。
        boolean completeRisk = true;
        for (boolean /* 该分片或引擎实例是否已经在清单中出现。 */ present : risk) completeRisk &= present;
        if (!completeMatching || !completeRisk
                || modules.size() != topology.matchingEngineCount() + topology.riskEngineCount()
                || maximumModuleSequence != matcherSequence) {
            throw new IllegalArgumentException("incomplete matcher snapshot modules");
        }
    }

    /**
     * 从清单拓扑返回撮合引擎数量。
     */
    public int matchingEngineCount() { return topology.matchingEngineCount(); }
    /**
     * 从清单拓扑返回风险引擎数量。
     */
    public int riskEngineCount() { return topology.riskEngineCount(); }
    /**
     * 返回清单拓扑的撮合分片路由掩码。
     */
    public int matcherShardMask() { return topology.matcherShardMask(); }
    /**
     * 返回清单拓扑的账户 Lane 数量。
     */
    public int accountLaneCount() { return topology.accountLaneCount(); }
    /**
     * 返回清单保存的用户到账户 Lane 路由种子。
     */
    public long accountLaneSeed() { return topology.accountLaneSeed(); }

    /**
     * 按分片编号加一取对应进度，检查范围及列表中的编号仍一致；控制分片负一位于第零项。
     * @param matcherShardId 需读取进度的分片编号，控制分片为负一
     */
    public MatcherShardProgress progress(int matcherShardId) {
        // 分片编号加一后的规定位置，控制负一对应零。
        int index = matcherShardId + 1;
        if (index < 0 || index >= matcherShardProgress.size()) {
            throw new IllegalArgumentException("matcher shard is outside snapshot topology");
        }
        // 按逻辑下标取出的分片进度，返回前再核对分片编号。
        MatcherShardProgress progress = matcherShardProgress.get(index);
        if (progress.matcherShardId() != matcherShardId) {
            throw new IllegalStateException("matcher shard progress is not canonically ordered");
        }
        return progress;
    }

    /**
     * 只要求给定 Core 状态非空，并取其产品线与调用方提供的预期序号和哈希核对清单；本方法不计算 state.businessStateHash，也不逐项核对订单或余额。
     * @param state 需要与本清单配对的 Core 业务状态，本方法只直接读取其产品线
     * @param expectedCoreSequence 由外部恢复清单提供的预期 Core 水位
     * @param expectedCoreBusinessStateHash 调用方提供的预期 Core 业务哈希，不在本方法内重算
     */
    public void verifyCoreState(TradingCoreState state, long expectedCoreSequence,
                                long expectedCoreBusinessStateHash) {
        if (state == null) throw new IllegalStateException("Core snapshot state is missing");
        verifyCoreManifest(state.productLine(), expectedCoreSequence, expectedCoreBusinessStateHash);
    }

    /**
     * 逐项要求产品线、Core 序号和业务哈希与清单相同；不在此重算引擎哈希、核对模块内容或执行恢复。
     * @param expectedProductLine 调用方当前产品线
     * @param expectedCoreSequence 由外部恢复清单提供的预期 Core 水位
     * @param expectedBusinessStateHash 调用方提供的预期业务哈希
     */
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
