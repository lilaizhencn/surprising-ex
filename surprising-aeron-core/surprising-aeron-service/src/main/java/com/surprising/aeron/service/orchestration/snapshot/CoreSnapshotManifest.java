package com.surprising.aeron.service.orchestration.snapshot;

import com.surprising.aeron.service.lane.LaneTopology;
import com.surprising.product.api.ProductLine;

/**
 * 对外查看快照配对信息的清单，列出产品线、版本、Core/Matcher 水位、路由和校验值。它是已有解析结果的摘要，不持有账户或原生模块字节，构造器也不自行重算各项哈希。
 * @param productLine 该快照唯一所属的产品线
 * @param schemaVersion 外层快照格式版本号，独立于各内嵌负载自己的格式版本
 * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
 * @param coreSequence 快照覆盖的已应用 Core 命令水位
 * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
 * @param clusterPosition 捕获点的非负 Cluster 日志位置
 * @param appliedCommandCount 冻结点已应用的 Core 命令数量，须与 coreSequence 相同
 * @param matcherSequence 原生快照模块中的最大持久化序号，不是 Core 应用数量
 * @param businessStateHash Core 状态连同费率、待完成划转的业务哈希
 * @param engineStateHash Matcher 引擎模块汇总出的 int 哈希
 * @param sourceSequenceDigest 各消息来源已消费序号的汇总摘要
 * @param topology 快照保存的 Matcher 与账户 Lane 路由拓扑
 * @param globalFundsHash 头部保存的全局资金哈希
 * @param checksum 快照封套及非页尾段的 CRC32C 数值，以 long 承载
 */
public record CoreSnapshotManifest(
        ProductLine productLine,
        int schemaVersion,
        long snapshotId,
        long coreSequence,
        long clusterTimestamp,
        long clusterPosition,
        long appliedCommandCount,
        long matcherSequence,
        long businessStateHash,
        int engineStateHash,
        long sourceSequenceDigest,
        LaneTopology topology,
        long globalFundsHash,
        long checksum) {

    /**
     * 要求产品线/拓扑非空、版本为正、时间及序号非负，Core 水位等于已应用数量，资金哈希非零且校验值非负。此构造器允许快照号为零，也没有单独要求业务哈希非零；真实字节格式的更严检查在解析流程执行。
     */
    public CoreSnapshotManifest {
        if (productLine == null || schemaVersion <= 0 || snapshotId < 0 || coreSequence < 0 || clusterTimestamp < 0 || clusterPosition < 0
                || coreSequence != appliedCommandCount || matcherSequence < 0
                || topology == null || globalFundsHash == 0
                || checksum < 0) {
            throw new IllegalArgumentException("invalid core snapshot manifest");
        }
    }
}
