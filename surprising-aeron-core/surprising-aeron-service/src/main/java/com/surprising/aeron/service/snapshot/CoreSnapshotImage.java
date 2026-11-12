package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.result.CommandResultLog;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 编码前的同一冻结点输入：把 Core 交易状态、Matcher 快照、消费水位、幂等结果、费率、划转和各 Lane 清单配在一起。 */
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
        OrderBookSnapshot matcherSnapshot,
        TradingCoreState tradingState,
        Map<TradingCore.SourceKey, Long> sourceSequences,
        Map<UUID, CommandResultLog.StoredResult> commandResults,
        Map<Long, CoreFeePolicyState> feePolicies,
        Map<Long, TransferFundsCommand> pendingTransfers,
        byte[] completedOrderArchive,
        List<AccountLaneSnapshot> accountLanes) {

    public CoreSnapshotImage {
        if (productLine == null || appliedCommandCount < 0 || snapshotId <= 0 || projectionSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0
                || businessStateHash == 0 || fundsStateHash == 0
                || matcherSnapshot == null || tradingState == null
                || completedOrderArchive == null || tradingState.productLine() != productLine
                || sourceSequences == null || commandResults == null || feePolicies == null
                || pendingTransfers == null || accountLanes == null) {
            throw new IllegalArgumentException("invalid core snapshot image");
        }
    }

}
