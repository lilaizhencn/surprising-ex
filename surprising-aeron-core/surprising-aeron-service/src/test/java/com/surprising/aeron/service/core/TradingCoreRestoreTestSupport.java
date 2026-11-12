package com.surprising.aeron.service.core;

import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.matcher.OrderBookEngine;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.result.CommandResultLog;
import com.surprising.aeron.service.retention.CompletedOrderArchive;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 测试用运行时恢复助手：把夹具已有的账户、幂等与来源进度交给真实恢复入口，再补账户 Lane 快照并激活候选。它只组装当前测试需要的输入，不代替完整生产快照解析。
 */
public final class TradingCoreRestoreTestSupport {

    /**
     * 工具类不保存实例状态，禁止外部创建对象。
     */
    private TradingCoreRestoreTestSupport() {
    }

    /** 为独立原生订单簿夹具捕获真实分片模块，不建立第二套生产快照流程。 */
    public static OrderBookSnapshot matcherSnapshot(OrderBookEngine adapter,
            long snapshotId, long sequence, long businessStateHash, TradingCoreState state,
            Iterable<com.surprising.aeron.model.CoreOrderState> activeOrders) {
        var shards = new java.util.ArrayList<OrderBookEngine.MatcherShardSnapshot>();
        try {
            for (int shardId = 0; shardId < adapter.topology().matchingEngineCount(); shardId++)
                shards.add(adapter.captureShardSnapshot(shardId, snapshotId, sequence, activeOrders));
            return adapter.finishShardedSnapshot(snapshotId, sequence, businessStateHash, state, shards);
        } catch (RuntimeException | Error failure) {
            adapter.discardSnapshot(snapshotId);
            throw failure;
        }
    }

    /**
     * 没有 Matcher 快照时要求账户不存在未结束订单；用临时确定性适配器生成空 Matcher 快照，再交给完整参数入口恢复。
     * @param productLine 当前夹具所属产品线
     * @param appliedCommandCount 已应用命令进度，交给现有恢复契约
     * @param commandResults 恢复所需的命令幂等结果
     * @param lastSourceSequences 各来源最后接收序号
     * @param snapshotState 夹具给出的账户及业务状态
     */
    public static TradingCore restore(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, CommandResultLog.StoredResult> commandResults,
            Map<TradingCore.SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState) {
        if (!snapshotState.orders().isEmpty()) {
            throw new IllegalArgumentException("matcher snapshot is required for restored open orders");
        }
        // 为无未结束订单夹具构造的 Matcher 快照
        OrderBookSnapshot matcherSnapshot;
        try (/* 本方法临时持有并自动关闭的确定性撮合适配器 */ OrderBookEngine adapter = new OrderBookEngine()) {
            matcherSnapshot = matcherSnapshot(adapter, 1, appliedCommandCount,
                    snapshotState.businessStateHash(), snapshotState, List.of());
        }
        return restore(productLine, appliedCommandCount, commandResults, lastSourceSequences,
                snapshotState, matcherSnapshot);
    }

    /**
     * 准备未激活候选，恢复与其投影序号对应的账户 Lane 快照，最后激活并返回；运行时异常时尝试关闭候选，关闭异常仍可能覆盖原异常。
     * @param productLine 当前夹具所属产品线
     * @param appliedCommandCount 已应用命令进度，交给现有恢复契约
     * @param commandResults 恢复所需的命令幂等结果
     * @param lastSourceSequences 各来源最后接收序号
     * @param snapshotState 夹具给出的账户及业务状态
     * @param matcherSnapshot 与该状态配对的 Matcher 快照
     */
    public static TradingCore restore(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, CommandResultLog.StoredResult> commandResults,
            Map<TradingCore.SourceKey, Long> lastSourceSequences,
            TradingCoreState snapshotState,
            OrderBookSnapshot matcherSnapshot) {
        // 尚在组装恢复的候选运行时，成功激活后才返回
        TradingCore candidate = TradingCore.prepareRestore(productLine, appliedCommandCount,
                commandResults, lastSourceSequences, snapshotState, new CompletedOrderArchive(),
                matcherSnapshot, appliedCommandCount, Map.of(), Map.of());
        try {
            // 候选要求的账户投影序号
            long projectionSequence = candidate.realtimeExportSequence();
            // 按该投影序号生成的账户 Lane 快照集合
            var accountLanes = candidate.accountLaneSnapshots(projectionSequence, snapshotState);
            candidate.restoreAccountLaneSnapshots(accountLanes, projectionSequence);
            candidate.activate();
            return candidate;
        } catch (/* 恢复或激活失败的运行时异常，尝试关闭候选后继续抛出 */ RuntimeException failure) {
            candidate.close();
            throw failure;
        }
    }
}
