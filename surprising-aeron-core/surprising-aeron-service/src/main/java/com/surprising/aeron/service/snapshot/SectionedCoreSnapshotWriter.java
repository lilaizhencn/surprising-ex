package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.MatcherSnapshotCodec;
import com.surprising.aeron.service.result.CommandResultLedger;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.CoreFeePolicySnapshotCodec;
import com.surprising.aeron.service.snapshot.state.CoreTransferSnapshotCodec;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32C;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 把同一快照边界的运行时状态编码成有序字节片段。捕获阶段校验 Core/Matcher 配对，编码阶段依次生成元数据、消费水位、命令结果、原生模块、交易状态、费率、划转、终态保留及各 Lane 段，最后写 CRC 页尾。调用方负责冻结和输入生命周期，本类不暂停交易线程。 构造约定：禁止实例化静态快照写入工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotWriter {

    /**
     * 从已建立边界的运行时捕获镜像，再交给分段编码；不是自行建立或等待快照屏障。
     * @param state 已建立一致性读取边界的交易运行时
     * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
     * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
     * @param coreSequence 快照覆盖的已应用 Core 命令水位
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     */
    public static SectionedCoreSnapshotCodec.SectionedSnapshot encode(
            TradingCoreRuntime state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        return encode(capture(state, matcherSnapshot, snapshotId, coreSequence,
                clusterTimestamp, clusterPosition));
    }

    /**
     * 拒绝仍有待撮合命令的运行时，物化交易状态并重算业务与资金哈希，核对 Matcher 清单、快照号、Core 数量及 Cluster 元数据，收集水位、结果、终态索引和账户 Lane 清单。其余 Lane/补丁就绪要求由外层屏障先保证。
     * @param state 已建立一致性读取边界的交易运行时
     * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
     * @param snapshotId 本次快照编号，具体合法范围由当前入口检查
     * @param coreSequence 快照覆盖的已应用 Core 命令水位
     * @param clusterTimestamp 捕获点的非负 Cluster 业务时间
     * @param clusterPosition 捕获点的非负 Cluster 日志位置
     */
    static CoreSnapshotImage capture(
            TradingCoreRuntime state,
            MatcherSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition) {
        if (!state.pendingMatching.isEmpty()) {
            throw new IllegalStateException("pending matcher continuations cannot be snapshotted");
        }
        // 捕获点物化的交易状态；header 方法中仅取得引用而未继续使用。
        var snapshotState = state.snapshotTradingState();
        // 物化状态连同当前费率与待划转重算的业务哈希。
        long businessStateHash = TradingCoreRuntime.canonicalBusinessStateHash(
                snapshotState.businessStateHash(), state.feePolicies(), state.pendingTransfers());
        // 从物化交易状态重算的资金哈希。
        long fundsStateHash = com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(snapshotState);
        matcherSnapshot.verifyCoreManifest(state.productLine(), state.appliedCommandCount(), businessStateHash);
        if (snapshotId != matcherSnapshot.snapshotId() || coreSequence != matcherSnapshot.coreSequence()
                || coreSequence != state.appliedCommandCount() || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalStateException("snapshot fence and matcher manifest do not match");
        }
        return new CoreSnapshotImage(state.productLine(), state.appliedCommandCount(),
                state.sourceSequenceIndex().digest(), snapshotId, coreSequence, state.commits.publication.publishedSequence(),
                businessStateHash, fundsStateHash,
                clusterTimestamp, clusterPosition, matcherSnapshot, snapshotState,
                state.lastSourceSequences(), state.commandResults(),
                state.feePolicies(), state.pendingTransfers(),
                state.terminalRetention().encode(),
                state.accountLaneSnapshots(coreSequence, snapshotState));
    }

    /**
     * 核对镜像的 Matcher 元数据后按固定段序编码，验证账户段数量、单段及总大小，生成小端封套/段头和 CRC 页尾。这里不调用 image.verifyFullState；需要完整审计时由生命周期入口先执行，嵌套负载各用自己的格式。
     * @param image 输入引用在编码期间保持稳定的快照镜像
     */
    public static SectionedCoreSnapshotCodec.SectionedSnapshot encode(CoreSnapshotImage image) {
        // 本次镜像已经配对的 Matcher 快照引用。
        MatcherSnapshot matcherSnapshot = image.matcherSnapshot();
        // 捕获点物化的交易状态；header 方法中仅取得引用而未继续使用。
        var snapshotState = image.tradingState();
        matcherSnapshot.verifyCoreManifest(image.productLine(), image.appliedCommandCount(),
                image.businessStateHash());
        // 按固定协议顺序排列的业务负载列表，尚未加上 CRC 页尾。
        ArrayList<byte[]> payloads = new ArrayList<>();
        payloads.add(header(image));
        payloads.add(sources(image.sourceSequences()));
        payloads.add(results(image.commandResults()));
        payloads.add(MatcherSnapshotCodec.encode(matcherSnapshot));
        payloads.add(TradingStateSnapshotCodec.encode(snapshotState));
        payloads.add(CoreFeePolicySnapshotCodec.encode(image.feePolicies()));
        payloads.add(CoreTransferSnapshotCodec.encode(image.pendingTransfers()));
        payloads.add(image.terminalRetention());
        for (AccountLaneSnapshot /* 当前追加编码的账户 Lane 快照，顺序沿用镜像列表。 */ lane : image.accountLanes()) {
            payloads.add(accountLane(lane));
        }
        // 所有业务负载再加一个 CRC 页尾的总段数。
        int sectionCount = Math.addExact(payloads.size(), 1);
        if (sectionCount != SectionedCoreSnapshotCodec.sectionCount(matcherSnapshot.accountLaneCount())) {
            throw new IllegalStateException("account lane snapshot section count mismatch");
        }
        // 包含封套、所有段头、业务负载及页尾的 long 总长度，检查上限后才转 int。
        long totalLength = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                + (long) sectionCount * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                + SectionedCoreSnapshotCodec.FOOTER_LENGTH;
        for (byte[] /* 当前检查长度或写入片段列表的业务负载数组。 */ payload : payloads) {
            requireSectionLength(payload.length);
            totalLength = Math.addExact(totalLength, payload.length);
        }
        if (totalLength > SectionedCoreSnapshotCodec.MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("core snapshot exceeds maximum size");
        }

        // 已编码的十二字节小端封套，首先加入 CRC。
        byte[] envelope = ByteBuffer.allocate(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SectionedCoreSnapshotCodec.MAGIC)
                .putShort((short) SectionedCoreSnapshotCodec.VERSION)
                .putShort((short) 0)
                .putInt(sectionCount)
                .array();
        // 累计封套和全部非页尾段头/负载的 CRC32C。
        CRC32C checksum = new CRC32C();
        checksum.update(envelope, 0, envelope.length);
        // 可逐片持久化的外层有序片段列表，包含封套、各段头和负载。
        ArrayList<byte[]> chunks = new ArrayList<>(1 + sectionCount * 2);
        chunks.add(envelope);
        for (/* 当前业务负载的零基下标，协议段号使用加一。 */ int index = 0; index < payloads.size(); index++) {
            // 当前检查长度或写入片段列表的业务负载数组。
            byte[] payload = payloads.get(index);
            // 当前业务负载的八字节段头，也计入 CRC。
            byte[] sectionHeader = sectionHeader(index + 1, payload.length);
            chunks.add(sectionHeader);
            chunks.add(payload);
            checksum.update(sectionHeader, 0, sectionHeader.length);
            checksum.update(payload, 0, payload.length);
        }
        // 用八字节小端 long 承载的 CRC 数值，页尾段头和页尾自身不进入 CRC。
        byte[] footer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.FOOTER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(checksum.getValue()).array();
        chunks.add(sectionHeader(sectionCount, footer.length));
        chunks.add(footer);
        return new SectionedCoreSnapshotCodec.SectionedSnapshot(chunks, Math.toIntExact(totalLength));
    }

    /**
     * 编码固定元数据，包含路由参数、Core/投影水位、投影反码、Lane 摘要、Cluster 位置和各哈希；本地 snapshotState 引用未被后续语句读取，业务哈希使用镜像已保存值。
     * @param image 输入引用在编码期间保持稳定的快照镜像
     */
    private static byte[] header(CoreSnapshotImage image) {
        // 本次镜像已经配对的 Matcher 快照引用。
        var matcherSnapshot = image.matcherSnapshot();
        // 捕获点物化的交易状态；header 方法中仅取得引用而未继续使用。
        var snapshotState = image.tradingState();
        // 当前段或条目的已分配小端写入缓冲。
        ByteBuffer buffer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) ProductLineWireCode.encode(image.productLine()))
                .putInt(matcherSnapshot.matchingEngineCount())
                .putInt(matcherSnapshot.riskEngineCount())
                .putInt(matcherSnapshot.matcherShardMask())
                .putInt(matcherSnapshot.accountLaneCount())
                .putLong(matcherSnapshot.accountLaneSeed())
                .putInt(matcherSnapshot.topology().matcherWindowSize())
                .putInt(matcherSnapshot.topology().matchingCompletionCapacity())
                .putInt(matcherSnapshot.topology().accountLaneQueueCapacity())
                .putLong(image.appliedCommandCount())
                .putLong(image.snapshotId())
                .putLong(image.coreSequence())
                .putLong(image.projectionSequence())
                .putLong(~image.projectionSequence())
                .putLong(SectionedCoreSnapshotValidation.accountLaneDigest(image.accountLanes()))
                .putLong(image.clusterTimestamp())
                .putLong(image.clusterPosition())
                .putLong(image.businessStateHash())
                .putLong(image.fundsStateHash())
                .putLong(image.sourceSequenceDigest());
        return buffer.array();
    }

    /**
     * 用精确加乘法分配一个 Lane 段，依次写编号、修订、应用/提交水位、状态/资金哈希及用户数量和现有用户顺序。
     * @param lane 一个账户 Lane 的冻结水位与用户清单
     */
    private static byte[] accountLane(AccountLaneSnapshot lane) {
        // 当前 Lane、来源或幂等结果段所需的精确字节长度。
        int length = Math.addExact(Integer.BYTES * 2 + Long.BYTES * 5,
                Math.multiplyExact(lane.userIds().size(), Long.BYTES));
        // 当前段或条目的已分配小端写入缓冲。
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(lane.laneId()).putLong(lane.revision()).putLong(lane.appliedSequence())
                .putLong(lane.committedSequence()).putLong(lane.localStateHash())
                .putLong(lane.localFundsHash()).putInt(lane.userIds().size());
        lane.userIds().forEach(buffer::putLong);
        return buffer.array();
    }

    /**
     * 按来源 Map 当前迭代顺序写条目数、来源码、零保留位、来源实例和消费水位；本方法不额外排序，条目固定二十四字节。
     * @param sourceSequences 按来源类型及来源实例记录的消费水位表
     */
    private static byte[] sources(Map<TradingCoreRuntime.SourceKey, Long> sourceSequences) {
        // 来源水位 Map 的条目数量。
        int count = sourceSequences.size();
        // 当前 Lane、来源或幂等结果段所需的精确字节长度。
        int length = Math.toIntExact(Integer.BYTES + Math.multiplyExact(
                (long) count, SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH));
        // 当前段或条目的已分配小端写入缓冲。
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(count);
        sourceSequences.forEach((/* 当前来源类型与来源实例号组成的键。 */ sourceKey, /* 该来源已经消费的命令序号。 */ sequence) -> {
            buffer.putInt(sourceKey.source().wireCode());
            buffer.putInt(0);
            buffer.putLong(sourceKey.sourceId());
            buffer.putLong(sequence);
        });
        return buffer.array();
    }

    /**
     * 先精确累计所有命令结果条目长度并检查单段上限，再按 Map 当前迭代顺序写条目数与每条结果。
     * @param commandResults 按命令 UUID 保存的幂等结果表
     */
    private static byte[] results(Map<UUID, CommandResultLedger.StoredResult> commandResults) {
        // 当前 Lane、来源或幂等结果段所需的精确字节长度。
        long length = Integer.BYTES;
        for (CommandResultLedger.StoredResult /* 当前需要计长或编码的一条幂等结果。 */ result : commandResults.values()) {
            length = Math.addExact(length, Math.addExact(Integer.BYTES, resultEntryLength(result)));
        }
        requireSectionLength(Math.toIntExact(length));
        // 当前段或条目的已分配小端写入缓冲。
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(commandResults.size());
        commandResults.forEach((/* 当前结果 Map 条目对应的命令 UUID。 */ commandId, /* 当前需要计长或编码的一条幂等结果。 */ result) -> putResult(buffer, commandId, result));
        return buffer.array();
    }

    /**
     * 写一条命令 UUID、固定指纹、状态/结果码、已应用数量、保留序号及响应字节，前置长度不包含它自己的四字节。
     * @param buffer 已分配且定位到条目写入位置的小端目标缓冲
     * @param commandId 该幂等结果对应的原命令 UUID
     * @param result 保存原指纹、终态和响应数据的幂等结果
     */
    private static void putResult(ByteBuffer buffer, UUID commandId, CommandResultLedger.StoredResult result) {
        // 当前结果的响应负载字节，用于长度及实际写入。
        byte[] responseData = result.responseData();
        buffer.putInt(resultEntryLength(result));
        buffer.putLong(commandId.getMostSignificantBits());
        buffer.putLong(commandId.getLeastSignificantBits());
        buffer.put(result.fingerprint().bytes());
        buffer.putInt(result.status().wireCode());
        buffer.putInt(result.resultCode().wireCode());
        buffer.putLong(result.appliedCommandCount());
        buffer.putLong(result.retentionSequence());
        buffer.putInt(responseData.length);
        buffer.put(responseData);
    }

    /**
     * 返回固定七十六字节加响应数据长度，以精确加法防止条目大小溢出。
     * @param result 保存原指纹、终态和响应数据的幂等结果
     */
    private static int resultEntryLength(CommandResultLedger.StoredResult result) {
        return Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH, result.responseData().length);
    }

    /**
     * 生成八字节小端段头，包含一基顺序段号和负载字节数，不在此额外检查参数。
     * @param id 一基顺序段编号
     * @param payloadLength 该段的负载字节数
     */
    private static byte[] sectionHeader(int id, int payloadLength) {
        return ByteBuffer.allocate(SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(payloadLength).array();
    }

    /**
     * 要求单段负载长度为正且不超过部署单段上限；总大小另由编码流程检查。
     * @param length 需要检查的单段负载长度
     */
    private static void requireSectionLength(int length) {
        if (length <= 0 || length > SectionedCoreSnapshotCodec.MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("invalid snapshot section length");
        }
    }
}
