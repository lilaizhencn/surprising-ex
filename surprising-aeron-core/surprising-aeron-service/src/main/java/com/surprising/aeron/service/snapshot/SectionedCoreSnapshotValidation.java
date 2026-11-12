package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 解析元数据并核对各快照组件是否来自同一冻结点。不读取正在运行的订单簿，也不在这里替代恢复后的原生逐单核对。构造约定：禁止实例化静态快照配对检查工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SectionedCoreSnapshotValidation {

    /** 按固定小端字段读取元数据，使用快照保存的路由参数构造并校验拓扑，检查预期产品线、正快照号、非负水位/时间及投影水位反码。必须读尽该段。 */
    static HeaderManifest parseHeader(byte[] encoded, ProductLine expectedProductLine) {
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(header.get()));
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        long appliedCommandCount = header.getLong();
        long snapshotId = header.getLong();
        long projectionSequence = header.getLong();
        long projectionSequenceComplement = header.getLong();
        long accountLaneDigest = header.getLong();
        long clusterTimestamp = header.getLong();
        long clusterPosition = header.getLong();
        long businessStateHash = header.getLong();
        long globalFundsHash = header.getLong();
        long sourceSequenceDigest = header.getLong();
        if (header.hasRemaining()) throw new ProtocolException("snapshot header section has trailing garbage");
        if (snapshotId <= 0) throw new ProtocolException("snapshot id mismatch");
        if (projectionSequenceComplement != ~projectionSequence) {
            throw new ProtocolException("snapshot projection sequence mismatch");
        }
        if (appliedCommandCount < 0 || projectionSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new ProtocolException("invalid snapshot sequence or position");
        }
        return new HeaderManifest(productLine, snapshotId,
                projectionSequence, accountLaneDigest,
                clusterTimestamp, clusterPosition, appliedCommandCount,
                businessStateHash, globalFundsHash, sourceSequenceDigest);
    }

    /** 把头部与解码后的 Core、Matcher、来源及费率/划转逐项配对：产品线/拓扑/快照号/序号一致，业务与资金哈希重算一致，来源摘要一致。 */
    static void validatePairing(
            HeaderManifest manifest,
            Map<TradingCore.SourceKey, Long> sourceSequences,
            OrderBookSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers) {
        requireMatch(manifest.productLine() == matcherSnapshot.productLine()
                && manifest.productLine() == tradingState.productLine(), "product line");
        requireMatch(manifest.snapshotId() == matcherSnapshot.snapshotId(), "snapshot id");
        requireMatch(manifest.appliedCommandCount() == matcherSnapshot.coreSequence(), "core sequence");
        requireMatch(manifest.businessStateHash() == TradingCore.canonicalBusinessStateHash(
                tradingState.businessStateHash(), feePolicies, pendingTransfers)
                && manifest.businessStateHash() == matcherSnapshot.coreBusinessStateHash(), "business state hash");
        requireMatch(manifest.globalFundsHash()
                == com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(tradingState), "funds hash");
        requireMatch(manifest.sourceSequenceDigest() == TradingCore.sourceSequenceDigest(sourceSequences),
                "source sequence digest");
    }

    /** 重算给定 Lane 列表摘要并与头部比对。具体用户路由、水位和状态内容由解析与恢复层继续检查。 */
    static void validateAccountLanes(HeaderManifest manifest, List<AccountLaneSnapshot> lanes) {
        requireMatch(manifest.accountLaneDigest() == accountLaneDigest(lanes), "account lane hash");
    }

    /** 按传入列表及用户列表现有顺序折叠 Lane 编号、修订、水位、状态/资金哈希和用户号。本方法不排序、不验证重复，int/long 自然溢出属于哈希运算。 */
    static long accountLaneDigest(List<AccountLaneSnapshot> lanes) {
        if (lanes == null) throw new IllegalArgumentException("account lanes are required");
        long hash = 0xcbf29ce484222325L;
        for (AccountLaneSnapshot lane : lanes) {
            hash = mix(hash, lane.laneId());
            hash = mix(hash, lane.revision());
            hash = mix(hash, lane.appliedSequence());
            hash = mix(hash, lane.committedSequence());
            hash = mix(hash, lane.localStateHash());
            hash = mix(hash, lane.localFundsHash());
            for (Long userId : lane.userIds()) hash = mix(hash, userId);
        }
        return hash;
    }

    /** 对一个长整数执行固定的异或和乘法哈希混合，允许 long 自然溢出，不用于金额计算。 */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static void requireMatch(boolean matches, String field) {
        if (!matches) throw new ProtocolException("snapshot " + field + " mismatch");
    }

    /** 从固定元数据段读取的内部清单，额外保留投影水位与 Lane 摘要供恢复核对。 */
    record HeaderManifest(
            ProductLine productLine, long snapshotId,
            long projectionSequence, long accountLaneDigest,
            long clusterTimestamp, long clusterPosition, long appliedCommandCount,
            long businessStateHash, long globalFundsHash,
            long sourceSequenceDigest) {
    }
}
