package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookSnapshot;
import com.surprising.aeron.service.matcher.OrderBookSnapshotCodec;
import com.surprising.aeron.service.result.CommandResultLog;
import com.surprising.aeron.service.retention.CompletedOrderArchive;
import com.surprising.aeron.service.snapshot.SectionedCoreSnapshotValidation.HeaderManifest;
import com.surprising.aeron.service.snapshot.state.AccountLaneSnapshot;
import com.surprising.aeron.service.snapshot.state.CoreFeePolicySnapshotCodec;
import com.surprising.aeron.service.snapshot.state.CoreTransferSnapshotCodec;
import com.surprising.aeron.service.snapshot.state.TradingStateSnapshotCodec;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 把已经按段接收且通过外层 CRC 的负载解成 Core、Matcher 和运行时辅助组件，并核对同一冻结点的哈希、路由与水位。恢复入口随后创建候选运行时、装回各 Lane 状态并激活原生订单簿。解析清单时只使用组件，不启动运行线程。构造约定：禁止实例化静态组件解析工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotParser {

    /** 按固定段位置依次解码元数据、来源、幂等结果、Matcher、交易状态、费率、划转和 Lane 清单，检查 Lane 数量、摘要及整体配对，再解码终态保留索引并返回组件。假定外层 Recovery 已确认段结构及 CRC，不在这里重新校验 CRC。 */
    static Components parse(byte[][] payloads, ProductLine expectedProductLine,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        HeaderManifest manifest = SectionedCoreSnapshotValidation.parseHeader(payloads[0], expectedProductLine);
        Map<TradingCore.SourceKey, Long> sourceSequences = parseSources(payloads[1]);
        Map<UUID, CommandResultLog.StoredResult> commandResults = parseResults(payloads[2]);
        OrderBookSnapshot matcherSnapshot = OrderBookSnapshotCodec.decode(payloads[3], deploymentTopology);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(payloads[4], manifest.productLine());
        Map<Long, CoreFeePolicyState> feePolicies = CoreFeePolicySnapshotCodec.decode(payloads[5]);
        Map<Long, TransferFundsCommand> pendingTransfers = CoreTransferSnapshotCodec.decode(payloads[6]);
        int laneSectionCount = payloads.length - SectionedCoreSnapshotCodec.BASE_SECTION_COUNT;
        if (laneSectionCount != matcherSnapshot.topology().accountLaneCount()) {
            throw new ProtocolException("snapshot account lane section count mismatch");
        }
        List<AccountLaneSnapshot> accountLanes = new ArrayList<>(laneSectionCount);
        for (int index = 0; index < laneSectionCount; index++) {
            accountLanes.add(parseAccountLane(payloads[8 + index], matcherSnapshot.topology(), index));
        }
        SectionedCoreSnapshotValidation.validateAccountLanes(manifest, accountLanes);
        SectionedCoreSnapshotValidation.validatePairing(
                manifest, sourceSequences, matcherSnapshot, tradingState, feePolicies, pendingTransfers);
        long checksum = ByteBuffer.wrap(payloads[payloads.length - 1])
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        CompletedOrderArchive retention = CompletedOrderArchive.decode(payloads[7]);
        return new Components(manifest.productLine(), manifest.appliedCommandCount(),
                commandResults, sourceSequences, matcherSnapshot, tradingState, feePolicies,
                pendingTransfers, retention, accountLanes, manifest, checksum);
    }

    /** 构造器参数错误转为协议异常，列表顺序/重复等进一步约束由 AccountLaneSnapshot 自身检查。 */
    private static AccountLaneSnapshot parseAccountLane(
            byte[] payload, com.surprising.aeron.config.LaneTopology topology, int expectedLaneId) {
        ByteBuffer lane = wrap(payload);
        if (lane.remaining() < Integer.BYTES * 2 + Long.BYTES * 5) {
            throw new ProtocolException("truncated account lane section");
        }
        int laneId = lane.getInt();
        if (laneId != expectedLaneId) {
            throw new ProtocolException("account lane section route mismatch");
        }
        long revision = lane.getLong();
        long appliedSequence = lane.getLong();
        long committedSequence = lane.getLong();
        long localStateHash = lane.getLong();
        long localFundsHash = lane.getLong();
        int userCount = readCount(lane, 1_000_000, "account lane user");
        int userBytes = Math.multiplyExact(userCount, Long.BYTES);
        if (lane.remaining() != userBytes) {
            throw new ProtocolException("invalid account lane user section length");
        }
        List<Long> userIds = new ArrayList<>(userCount);
        for (int index = 0; index < userCount; index++) {
            long userId = lane.getLong();
            if (topology.accountLaneId(userId) != laneId) {
                throw new ProtocolException("account lane user route mismatch");
            }
            userIds.add(userId);
        }
        try {
            return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                    localStateHash, localFundsHash, userIds);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("invalid account lane snapshot: " + exception.getMessage());
        }
    }

    /** 读取有上限的来源数并要求段长度精确匹配，逐项检查来源码、非负水位与重复键，按输入顺序保存。 */
    private static Map<TradingCore.SourceKey, Long> parseSources(byte[] payload) {
        ByteBuffer sources = wrap(payload);
        int sourceCount = readCount(sources, TradingCore.Limits.MAX_SOURCE_SEQUENCES, "source sequence");
        if (sources.remaining() != (long) sourceCount * SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH) {
            throw new ProtocolException("invalid snapshot source section length");
        }
        Map<TradingCore.SourceKey, Long> sourceSequences = new LinkedHashMap<>();
        for (int index = 0; index < sourceCount; index++) {
            CommandSource source = CommandSource.fromWireCode(sources.getInt());
            long sourceId = sources.getLong();
            long sequence = sources.getLong();
            if (sequence < 0 || sourceSequences.put(new TradingCore.SourceKey(source, sourceId), sequence) != null) {
                throw new ProtocolException("invalid snapshot source sequence");
            }
        }
        return sourceSequences;
    }

    /** 读取有上限的幂等结果数，逐条解析并拒绝重复 UUID，完成后要求段内没有尾随垃圾。 */
    private static Map<UUID, CommandResultLog.StoredResult> parseResults(byte[] payload) {
        ByteBuffer results = wrap(payload);
        int resultCount = readCount(results, TradingCore.Limits.MAX_IDEMPOTENCY_RESULTS, "result");
        Map<UUID, CommandResultLog.StoredResult> commandResults = new LinkedHashMap<>();
        for (int index = 0; index < resultCount; index++) {
            SnapshotResult result = readResult(results);
            if (commandResults.put(result.commandId(), result.value()) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result");
            }
        }
        requireConsumed(results, "results");
        return commandResults;
    }

    /** 成功后恢复原 limit，异常时整次解析失败。 */
    private static SnapshotResult readResult(ByteBuffer source) {
        if (source.remaining() < Integer.BYTES) throw new ProtocolException("truncated snapshot command result");
        int encodedLength = source.getInt();
        if (encodedLength < SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH || encodedLength > source.remaining()) {
            throw new ProtocolException("invalid snapshot command result length");
        }
        int limit = source.limit();
        source.limit(source.position() + encodedLength);
        UUID commandId = new UUID(source.getLong(), source.getLong());
        byte[] fingerprint = new byte[CommandFingerprint.LENGTH];
        source.get(fingerprint);
        ResponseStatus status = ResponseStatus.fromWireCode(source.getInt());
        CoreResultCode resultCode = CoreResultCode.fromWireCode(source.getInt());
        long appliedCommandCount = source.getLong();
        long retentionSequence = source.getLong();
        int responseLength = source.getInt();
        if (appliedCommandCount < 0 || retentionSequence <= 0
                || responseLength < 0 || responseLength != source.remaining()) {
            throw new ProtocolException("invalid snapshot command result metadata");
        }
        byte[] responseData = new byte[responseLength];
        source.get(responseData);
        source.limit(limit);
        return new SnapshotResult(commandId, new CommandResultLog.StoredResult(
                CommandFingerprint.fromBytes(fingerprint), status, resultCode, appliedCommandCount,
                responseData, retentionSequence));
    }

    private static int readCount(ByteBuffer buffer, int maximum, String label) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new ProtocolException("truncated snapshot " + label + " count");
        }
        int count = buffer.getInt();
        if (count < 0 || count > maximum) {
            throw new ProtocolException("invalid snapshot " + label + " count");
        }
        return count;
    }

    private static ByteBuffer wrap(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** 段内仍有未读取字节时明确拒绝尾随垃圾，防止只解析合法前缀。 */
    private static void requireConsumed(ByteBuffer buffer, String section) {
        if (buffer.hasRemaining()) {
            throw new ProtocolException("snapshot " + section + " section has trailing garbage");
        }
    }

    /** 解析一条幂等记录时使用的 UUID 与结果值配对，不是新执行业务产生的响应。 */
    private record SnapshotResult(UUID commandId, CommandResultLog.StoredResult value) {
    }

    /** 已经解码并完成组件配对的输入集合。保留状态与原生模块引用以及待移交的终态索引，可用于查看清单或准备、激活恢复运行时。 */
    record Components(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, CommandResultLog.StoredResult> commandResults,
            Map<TradingCore.SourceKey, Long> sourceSequences,
            OrderBookSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, CoreFeePolicyState> feePolicies,
            Map<Long, TransferFundsCommand> pendingTransfers,
            CompletedOrderArchive retention,
            List<AccountLaneSnapshot> accountLanes,
            HeaderManifest manifest,
            long checksum) {

        /** 核对产品线和账户 Lane 清单与 Core 状态配对，准备尚未激活的候选运行时，恢复 Lane 元数据后激活订单簿并返回。 */
        TradingCore restore(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration, boolean activate) {
            requireProductLine(expectedProductLine);
            TradingCore candidate = null;
            try {
                com.surprising.aeron.service.core.AccountBook.validateAccountLaneSnapshotManifest(
                        accountLanes, manifest.appliedCommandCount(), tradingState, matcherSnapshot.topology());
                candidate = TradingCore.prepareRestore(productLine, appliedCommandCount,
                        commandResults, sourceSequences, tradingState, retention, matcherSnapshot,
                        manifest.projectionSequence(), feePolicies, pendingTransfers, configuration);
                candidate.restoreAccountLaneSnapshots(accountLanes, manifest.appliedCommandCount());
                if (activate) candidate.activate();
                return candidate;
            } catch (RuntimeException exception) {
                if (candidate == null) retention.close();
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (RuntimeException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                }
                throw new com.surprising.aeron.protocol.ProtocolException(
                        "invalid restored snapshot state: " + exception.getMessage(), exception);
            }
        }

        /** 再次核对预期产品线后，从已解析元数据和 Matcher 构造公开清单，不创建运行时，也不计算新 CRC。 */
        CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            requireProductLine(expectedProductLine);
            return new CoreSnapshotManifest(productLine, SectionedCoreSnapshotCodec.VERSION,
                    manifest.snapshotId(), manifest.clusterTimestamp(),
                    manifest.clusterPosition(), appliedCommandCount, matcherSnapshot.matcherSequence(),
                    manifest.businessStateHash(), matcherSnapshot.engineStateHash(),
                    manifest.sourceSequenceDigest(), matcherSnapshot.topology(), checksum);
        }

        private void requireProductLine(ProductLine expectedProductLine) {
            if (productLine != expectedProductLine) {
                throw new ProtocolException("snapshot product line mismatch: " + productLine);
            }
        }
    }
}
