package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.matcher.OrderBookSnapshotCodec;
import com.surprising.aeron.service.result.CommandResultLog;
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

/** 把同一快照边界的运行时状态编码成有序字节片段。调用方负责冻结和输入生命周期，本类不暂停交易线程。构造约定：禁止实例化静态快照写入工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotWriter {

    /** 复用同一栅栏物化的状态、费率及划转，审计完整业务哈希并计算资金哈希，核对 Matcher 清单、快照号、Core 水位及 Cluster 元数据。 */
    static CoreSnapshotImage capture(
            TradingCore state,
            OrderBookSnapshot matcherSnapshot,
            long snapshotId,
            long coreSequence,
            long clusterTimestamp,
            long clusterPosition,
            com.surprising.aeron.service.snapshot.state.TradingCoreState snapshotState,
            Map<Long, com.surprising.aeron.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.protocol.TransferFundsCommand> pendingTransfers,
            long businessStateHash) {
        if (!state.pendingMatching().isEmpty()) {
            throw new IllegalStateException("pending matcher continuations cannot be snapshotted");
        }
        long fundsStateHash = com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(snapshotState);
        matcherSnapshot.verifyCoreManifest(state.productLine(), state.appliedCommandCount(), businessStateHash);
        if (snapshotId != matcherSnapshot.snapshotId() || coreSequence != matcherSnapshot.coreSequence()
                || coreSequence != state.appliedCommandCount() || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new IllegalStateException("snapshot fence and matcher manifest do not match");
        }
        CoreSnapshotImage image = new CoreSnapshotImage(state.productLine(), state.appliedCommandCount(),
                state.sourceSequenceIndex().digest(), snapshotId, state.commits.publication.publishedSequence(),
                businessStateHash, fundsStateHash,
                clusterTimestamp, clusterPosition, matcherSnapshot, snapshotState,
                state.lastSourceSequences(), state.commandResults(),
                feePolicies, pendingTransfers,
                state.completedOrderArchive().encode(),
                state.accountLaneSnapshots(coreSequence, snapshotState));
        if (TradingCore.canonicalBusinessStateHash(
                snapshotState.businessStateHash(), feePolicies, pendingTransfers) != businessStateHash) {
            throw new IllegalStateException("snapshot rolling hash differs from immutable state audit");
        }
        return image;
    }

    public static SectionedCoreSnapshotCodec.SectionedSnapshot encode(CoreSnapshotImage image, int maximumSnapshotBytes) {
        if (maximumSnapshotBytes <= 0 || maximumSnapshotBytes > SectionedCoreSnapshotCodec.HARD_MAX_SNAPSHOT_BYTES)
            throw new IllegalArgumentException("invalid snapshot byte limit");
        OrderBookSnapshot matcherSnapshot = image.matcherSnapshot();
        var snapshotState = image.tradingState();
        ArrayList<byte[]> payloads = new ArrayList<>();
        payloads.add(header(image));
        payloads.add(sources(image.sourceSequences()));
        payloads.add(results(image.commandResults()));
        long remainingMatcherBudget = maximumSnapshotBytes - (long) SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                - (long) SectionedCoreSnapshotCodec.sectionCount(matcherSnapshot.topology().accountLaneCount())
                    * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                - SectionedCoreSnapshotCodec.FOOTER_LENGTH;
        for (byte[] payload : payloads) remainingMatcherBudget -= payload.length;
        if (remainingMatcherBudget <= Long.BYTES)
            throw new IllegalArgumentException("core snapshot exceeds maximum size before matcher encoding");
        payloads.add(OrderBookSnapshotCodec.encode(matcherSnapshot, (int) Math.min(remainingMatcherBudget,
                SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES)));
        payloads.add(TradingStateSnapshotCodec.encode(snapshotState));
        payloads.add(CoreFeePolicySnapshotCodec.encode(image.feePolicies()));
        payloads.add(CoreTransferSnapshotCodec.encode(image.pendingTransfers()));
        payloads.add(image.completedOrderArchive());
        for (AccountLaneSnapshot lane : image.accountLanes()) {
            payloads.add(accountLane(lane));
        }
        int sectionCount = Math.addExact(payloads.size(), 1);
        if (sectionCount != SectionedCoreSnapshotCodec.sectionCount(matcherSnapshot.topology().accountLaneCount())) {
            throw new IllegalStateException("account lane snapshot section count mismatch");
        }
        long totalLength = SectionedCoreSnapshotCodec.ENVELOPE_LENGTH
                + (long) sectionCount * SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH
                + SectionedCoreSnapshotCodec.FOOTER_LENGTH;
        for (byte[] payload : payloads) {
            requireSectionLength(payload.length);
            totalLength = Math.addExact(totalLength, payload.length);
        }
        if (totalLength > maximumSnapshotBytes) {
            throw new IllegalArgumentException("core snapshot exceeds maximum size");
        }

        byte[] envelope = ByteBuffer.allocate(SectionedCoreSnapshotCodec.ENVELOPE_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SectionedCoreSnapshotCodec.MAGIC)
                .putShort((short) SectionedCoreSnapshotCodec.VERSION)
                .putShort((short) 0)
                .putInt(sectionCount)
                .array();
        CRC32C checksum = new CRC32C();
        checksum.update(envelope, 0, envelope.length);
        ArrayList<byte[]> chunks = new ArrayList<>(1 + sectionCount * 2);
        chunks.add(envelope);
        for (int index = 0; index < payloads.size(); index++) {
            byte[] payload = payloads.get(index);
            byte[] sectionHeader = sectionHeader(index + 1, payload.length);
            chunks.add(sectionHeader);
            chunks.add(payload);
            checksum.update(sectionHeader, 0, sectionHeader.length);
            checksum.update(payload, 0, payload.length);
        }
        byte[] footer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.FOOTER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putLong(checksum.getValue()).array();
        chunks.add(sectionHeader(sectionCount, footer.length));
        chunks.add(footer);
        return new SectionedCoreSnapshotCodec.SectionedSnapshot(chunks, Math.toIntExact(totalLength));
    }

    private static byte[] header(CoreSnapshotImage image) {
        ByteBuffer buffer = ByteBuffer.allocate(SectionedCoreSnapshotCodec.HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) ProductLineWireCode.encode(image.productLine()))
                .putLong(image.appliedCommandCount())
                .putLong(image.snapshotId())
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

    /** 用精确加乘法分配一个 Lane 段，依次写编号、修订、应用/提交水位、状态/资金哈希及用户数量和现有用户顺序。 */
    private static byte[] accountLane(AccountLaneSnapshot lane) {
        int length = Math.addExact(Integer.BYTES * 2 + Long.BYTES * 5,
                Math.multiplyExact(lane.userIds().size(), Long.BYTES));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(lane.laneId()).putLong(lane.revision()).putLong(lane.appliedSequence())
                .putLong(lane.committedSequence()).putLong(lane.localStateHash())
                .putLong(lane.localFundsHash()).putInt(lane.userIds().size());
        lane.userIds().forEach(buffer::putLong);
        return buffer.array();
    }

    /** 按来源 Map 当前迭代顺序写条目数、来源码、来源实例和消费水位。 */
    private static byte[] sources(Map<TradingCore.SourceKey, Long> sourceSequences) {
        int count = sourceSequences.size();
        int length = Math.toIntExact(Integer.BYTES + Math.multiplyExact(
                (long) count, SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH));
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).putInt(count);
        sourceSequences.forEach((sourceKey, sequence) -> {
            buffer.putInt(sourceKey.source().wireCode());
            buffer.putLong(sourceKey.sourceId());
            buffer.putLong(sequence);
        });
        return buffer.array();
    }

    /** 先精确累计所有命令结果条目长度并检查单段上限，再按 Map 当前迭代顺序写条目数与每条结果。 */
    private static byte[] results(Map<UUID, CommandResultLog.StoredResult> commandResults) {
        long length = Integer.BYTES;
        for (CommandResultLog.StoredResult result : commandResults.values()) {
            length = Math.addExact(length, Math.addExact(Integer.BYTES, resultEntryLength(result)));
        }
        requireSectionLength(Math.toIntExact(length));
        ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(commandResults.size());
        commandResults.forEach((commandId, result) -> putResult(buffer, commandId, result));
        return buffer.array();
    }

    private static void putResult(ByteBuffer buffer, UUID commandId, CommandResultLog.StoredResult result) {
        buffer.putInt(resultEntryLength(result));
        buffer.putLong(commandId.getMostSignificantBits());
        buffer.putLong(commandId.getLeastSignificantBits());
        buffer.put(result.fingerprint().bytes());
        buffer.putInt(result.status().wireCode());
        buffer.putInt(result.resultCode().wireCode());
        buffer.putLong(result.appliedCommandCount());
        buffer.putLong(result.retentionSequence());
        buffer.putInt(result.responseDataLength());
        buffer.put(result.responseDataUnsafe(), result.responseDataOffsetUnsafe(), result.responseDataLength());
    }

    /** 返回固定七十六字节加响应数据长度，以精确加法防止条目大小溢出。 */
    private static int resultEntryLength(CommandResultLog.StoredResult result) {
        return Math.addExact(SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH, result.responseDataLength());
    }

    /** 生成八字节小端段头，包含一基顺序段号和负载字节数，不在此额外检查参数。 */
    private static byte[] sectionHeader(int id, int payloadLength) {
        return ByteBuffer.allocate(SectionedCoreSnapshotCodec.SECTION_HEADER_LENGTH)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(payloadLength).array();
    }

    private static void requireSectionLength(int length) {
        if (length <= 0 || length > SectionedCoreSnapshotCodec.HARD_MAX_SECTION_BYTES) {
            throw new IllegalArgumentException("invalid snapshot section length");
        }
    }
}
