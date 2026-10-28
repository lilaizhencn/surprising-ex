package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.result.CommandResultLedger;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编码前的同一冻结点输入：把 Core 交易状态、Matcher 快照、消费水位、幂等结果、费率、划转和各 Lane 清单配在一起。record 只固定引用，不在构造器里复制 Map、List 或字节数组；调用方须在编码结束前保持输入稳定。完整哈希审计另由 verifyFullState 执行。
 * @param productLine 该快照唯一所属的产品线
 * @param appliedCommandCount 冻结点已应用的 Core 命令水位
 * @param sourceSequenceDigest 各消息来源已消费序号的汇总摘要
 * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
 * @param projectionSequence 冻结点已发布的实时投影水位，不要求等于 Core 命令数
 * @param businessStateHash Core 状态连同费率、待完成划转的业务哈希
 * @param fundsStateHash 冻结交易状态按资产与所有者计算的资金哈希
 * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
 * @param clusterPosition 捕获点的非负 Cluster 日志位置
 * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
 * @param tradingState 冻结点物化的交易状态，包含订单、账户与资金数据
 * @param sourceSequences 按来源类型及来源实例记录的消费水位表
 * @param commandResults 按命令 UUID 保存的幂等结果表
 * @param feePolicies 需要随快照保存的费率策略表
 * @param pendingTransfers 需要随快照保存的待完成划转表
 * @param terminalRetention 已经编码的终态保留索引字节，当前对象不复制数组
 * @param accountLanes 同一冻结点各账户 Lane 的序号、哈希和用户清单
 */
public record CoreSnapshotImage(
        ProductLine productLine,
        long appliedCommandCount,
        long sourceSequenceDigest,
        long snapshotId,
        long projectionSequence,
        long businessStateHash,
        long fundsStateHash,
        long clusterTimestamp,
        long clusterPosition,
        MatcherSnapshot matcherSnapshot,
        TradingCoreState tradingState,
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences,
        Map<UUID, CommandResultLedger.StoredResult> commandResults,
        Map<Long, CoreFeePolicyState> feePolicies,
        Map<Long, TransferFundsCommand> pendingTransfers,
        byte[] terminalRetention,
        List<AccountLaneSnapshot> accountLanes) {

    /**
     * 检查基本非空、非负序号、正快照号、非零业务/资金哈希及 Core 产品线匹配。这里只保存传入引用，不执行全量哈希或 Matcher 逐单对账。
     */
    public CoreSnapshotImage {
        if (productLine == null || appliedCommandCount < 0 || snapshotId <= 0 || projectionSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0
                || businessStateHash == 0 || fundsStateHash == 0
                || matcherSnapshot == null || tradingState == null
                || terminalRetention == null || tradingState.productLine() != productLine
                || sourceSequences == null || commandResults == null || feePolicies == null
                || pendingTransfers == null || accountLanes == null) {
            throw new IllegalArgumentException("invalid core snapshot image");
        }
    }

    /**
     * 先核对 Matcher 与 Core 的产品线、序号及业务哈希元数据，再从物化交易状态、费率及划转重算业务哈希和资金哈希；与保存值不同即拒绝。这里不重算来源摘要或 Lane 清单摘要。
     */
    void verifyFullState() {
        matcherSnapshot.verifyCoreState(tradingState, appliedCommandCount, businessStateHash);
        if (TradingCoreRuntime.canonicalBusinessStateHash(
                tradingState.businessStateHash(), feePolicies, pendingTransfers) != businessStateHash
                || com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(tradingState)
                != fundsStateHash) {
            throw new IllegalStateException("snapshot rolling hash differs from immutable state audit");
        }
    }

}
