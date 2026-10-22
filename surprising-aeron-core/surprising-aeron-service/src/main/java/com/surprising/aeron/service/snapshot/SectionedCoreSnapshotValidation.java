package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.config.LaneTopology;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 解析元数据并核对各快照组件是否来自同一冻结点。比较产品线、拓扑、快照号、Core 水位、业务/资金哈希、来源摘要及账户 Lane 摘要；不读取正在运行的订单簿，也不在这里替代恢复后的原生逐单核对。 构造约定：禁止实例化静态快照配对检查工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SectionedCoreSnapshotValidation {

    /**
     * 按固定小端字段读取元数据，使用快照保存的路由参数构造并校验拓扑，检查预期产品线、正快照号、非负水位/时间及投影水位反码；必须读尽该段。Core 数量与 Matcher 的配对另由 validatePairing 核对。
     * @param encoded 已确认固定段长度的元数据负载字节
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    static HeaderManifest parseHeader(byte[] encoded, ProductLine expectedProductLine) {
        // 元数据负载的小端读取视图。
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        // 外层固定编码解出的产品线，须与调用方预期一致。
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(header.get()));
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        // 从快照各分片参数构造的路由拓扑。
        LaneTopology topology;
        try {
            topology = new LaneTopology(header.getInt(), header.getInt(), header.getInt(),
                    header.getInt(), header.getLong(), header.getInt(), header.getInt(), header.getInt());
        } catch (/* 拓扑字段不合法的异常，转为带消息的协议异常。 */ IllegalArgumentException exception) {
            throw new ProtocolException("snapshot route mismatch: " + exception.getMessage());
        }
        // 头部记录的 Core 已应用数量，稍后与 Core 水位配对。
        long appliedCommandCount = header.getLong();
        // 头部读取的快照编号，要求为正。
        long snapshotId = header.getLong();
        // 头部读取的 Core 水位，要求非负。
        long coreSequence = header.getLong();
        // 独立的实时投影发布水位，要求非负。
        long projectionSequence = header.getLong();
        // 投影水位的按位反码，用于识别元数据错误。
        long projectionSequenceComplement = header.getLong();
        // 头部保存的各 Lane 清单摘要，之后与各段重算值比对。
        long accountLaneDigest = header.getLong();
        // 头部记录的非负 Cluster 业务时间。
        long clusterTimestamp = header.getLong();
        // 头部记录的非负 Cluster 日志位置。
        long clusterPosition = header.getLong();
        // 头部保存的业务哈希，配对时从状态及费率/划转重算。
        long businessStateHash = header.getLong();
        // 头部保存的资金哈希，配对时从交易状态重算。
        long globalFundsHash = header.getLong();
        // 头部保存的来源消费水位摘要，配对时从来源表重算。
        long sourceSequenceDigest = header.getLong();
        if (header.hasRemaining()) throw new ProtocolException("snapshot header section has trailing garbage");
        if (snapshotId <= 0) throw new ProtocolException("snapshot id mismatch");
        if (projectionSequenceComplement != ~projectionSequence) {
            throw new ProtocolException("snapshot projection sequence mismatch");
        }
        if (appliedCommandCount < 0 || coreSequence < 0 || projectionSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new ProtocolException("invalid snapshot sequence or position");
        }
        return new HeaderManifest(productLine, topology, snapshotId, coreSequence,
                projectionSequence, accountLaneDigest,
                clusterTimestamp, clusterPosition, appliedCommandCount,
                businessStateHash, globalFundsHash, sourceSequenceDigest);
    }

    /**
     * 把头部与解码后的 Core、Matcher、来源及费率/划转逐项配对：产品线/拓扑/快照号/序号一致，业务与资金哈希重算一致，来源摘要一致。划转标记为旧式隐含用户时按冻结的旧公式验证，不跳过校验。
     * @param manifest 从第一段解码的头部清单
     * @param sourceSequences 按来源类型及来源实例记录的消费水位表
     * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
     * @param tradingState 冻结点物化的交易状态，包含订单、账户与资金数据
     * @param feePolicies 需要随快照保存的费率策略表
     * @param pendingTransfers 需要随快照保存的待完成划转表
     * @param implicitTransferUsers 划转负载是否使用旧式隐含用户格式，决定其原业务哈希验证公式
     */
    static void validatePairing(
            HeaderManifest manifest,
            Map<TradingCoreRuntime.SourceKey, Long> sourceSequences,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.service.account.TransferRuntime> pendingTransfers,
            boolean implicitTransferUsers) {
        requireMatch(manifest.productLine() == matcherSnapshot.productLine()
                && manifest.productLine() == tradingState.productLine(), "product line");
        requireMatch(manifest.topology().equals(matcherSnapshot.topology()), "topology");
        requireMatch(manifest.snapshotId() == matcherSnapshot.snapshotId(), "snapshot id");
        requireMatch(manifest.coreSequence() == matcherSnapshot.coreSequence(), "core sequence");
        requireMatch(manifest.appliedCommandCount() == manifest.coreSequence(), "applied sequence");
        requireMatch(manifest.businessStateHash() == (implicitTransferUsers
                ? implicitUserBusinessHash(tradingState.businessStateHash(), feePolicies, pendingTransfers)
                : TradingCoreRuntime.canonicalBusinessStateHash(tradingState.businessStateHash(), feePolicies, pendingTransfers))
                && manifest.businessStateHash() == matcherSnapshot.coreBusinessStateHash(), "business state hash");
        requireMatch(manifest.globalFundsHash()
                == com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(tradingState), "funds hash");
        requireMatch(manifest.sourceSequenceDigest() == TradingCoreRuntime.sourceSequenceDigest(sourceSequences),
                "source sequence digest");
    }

    /**
     * 按划转 v1 的固定字段与顺序重算旧业务哈希：先合入费率，再按划转键排序，逐项混入发起用户和原命令字段。仅用于识别并校验已有旧负载，不改变现有划转事实。
     * @param base 尚未合入费率和待完成划转的 Core 业务哈希
     * @param policies 冻结点的费率策略表
     * @param transfers 按划转标识索引的待完成划转表
     */
    private static long implicitUserBusinessHash(long base,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.service.account.TransferRuntime> transfers) {
        base = TradingCoreRuntime.canonicalBusinessStateHash(base, policies, Map.of());
        if (transfers.isEmpty()) return base;
        // 按旧式划转字段累计的固定哈希。
        long digest = TradingCoreRuntime.HASH_OFFSET_BASIS;
        for (var /* 按划转键排序后当前正在折叠的待完成划转。 */ transfer : new java.util.TreeMap<>(transfers).values()) {
            // 该划转保存的原始请求字段，用于重现旧格式哈希。
            var command = transfer.command();
            digest = TradingCoreRuntime.mix(digest, transfer.userId());
            digest = TradingCoreRuntime.mix(digest, command.transferId());
            digest = TradingCoreRuntime.mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = TradingCoreRuntime.mix(digest, com.surprising.aeron.service.snapshot.state.SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = TradingCoreRuntime.mixText(digest, command.sourceAccountType());
            digest = TradingCoreRuntime.mixText(digest, command.targetAccountType());
            digest = TradingCoreRuntime.mixText(digest, command.asset());
            digest = TradingCoreRuntime.mix(digest, command.amountUnits());
            digest = TradingCoreRuntime.mixText(digest, command.referenceId());
            digest = TradingCoreRuntime.mixText(digest, command.reason());
        }
        return TradingCoreRuntime.mix(base, digest);
    }

    /**
     * 重算给定 Lane 列表摘要并与头部比对；具体用户路由、水位和状态内容由解析与恢复层继续检查。
     * @param manifest 从第一段解码的头部清单
     * @param lanes 按 Lane 顺序排列的账户快照清单
     */
    static void validateAccountLanes(HeaderManifest manifest, List<AccountLaneSnapshot> lanes) {
        requireMatch(manifest.accountLaneDigest() == accountLaneDigest(lanes), "account lane hash");
    }

    /**
     * 按传入列表及用户列表现有顺序折叠 Lane 编号、修订、水位、状态/资金哈希和用户号；本方法不排序、不验证重复，int/long 自然溢出属于哈希运算。
     * @param lanes 按 Lane 顺序排列的账户快照清单
     */
    static long accountLaneDigest(List<AccountLaneSnapshot> lanes) {
        if (lanes == null) throw new IllegalArgumentException("account lanes are required");
        // 账户 Lane 清单的固定哈希累加值。
        long hash = 0xcbf29ce484222325L;
        for (AccountLaneSnapshot /* 当前顺序折叠的一个 Lane 快照。 */ lane : lanes) {
            hash = mix(hash, lane.laneId());
            hash = mix(hash, lane.revision());
            hash = mix(hash, lane.appliedSequence());
            hash = mix(hash, lane.committedSequence());
            hash = mix(hash, lane.localStateHash());
            hash = mix(hash, lane.localFundsHash());
            for (Long /* 当前 Lane 用户清单中的一个编号，按现有顺序混入摘要。 */ userId : lane.userIds()) hash = mix(hash, userId);
        }
        return hash;
    }

    /**
     * 对一个长整数执行固定的异或和乘法哈希混合，允许 long 自然溢出，不用于金额计算。
     * @param hash 当前长整数哈希累加值
     * @param value 需要合入哈希的一个长整数
     */
    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    /**
     * 条件不成立就带上字段名抛 ProtocolException，统一指出快照配对的哪项不一致。
     * @param matches 当前字段配对是否成立
     * @param field 失败时用于协议异常的字段名称
     */
    private static void requireMatch(boolean matches, String field) {
        if (!matches) throw new ProtocolException("snapshot " + field + " mismatch");
    }

    /**
     * 从固定元数据段读取的内部清单，额外保留投影水位与 Lane 摘要供恢复核对；这是值载体，不在 record 构造时再次执行配对。
     * @param productLine 该快照唯一所属的产品线
     * @param topology 快照保存的 Matcher 与账户 Lane 路由拓扑
     * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
     * @param coreSequence 快照覆盖的已应用 Core 命令水位
     * @param projectionSequence 冻结点已发布的实时投影水位，不要求等于 Core 命令数
     * @param accountLaneDigest 头部保存的账户 Lane 清单摘要
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     * @param appliedCommandCount 冻结点已应用的 Core 命令数量，须与 coreSequence 相同
     * @param businessStateHash Core 状态连同费率、待完成划转的业务哈希
     * @param globalFundsHash 头部保存的全局资金哈希
     * @param sourceSequenceDigest 各消息来源已消费序号的汇总摘要
     */
    record HeaderManifest(
            ProductLine productLine, LaneTopology topology, long snapshotId, long coreSequence,
            long projectionSequence, long accountLaneDigest,
            long clusterTimestamp, long clusterPosition, long appliedCommandCount,
            long businessStateHash, long globalFundsHash,
            long sourceSequenceDigest) {
    }
}
