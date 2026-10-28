package com.surprising.aeron.service.snapshot;

import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.matcher.MatcherSnapshot;
import com.surprising.aeron.service.matcher.MatcherSnapshotCodec;
import com.surprising.aeron.service.result.CommandResultLedger;
import com.surprising.aeron.service.retention.TerminalStateRetention;
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

/** 把已经按段接收且通过外层 CRC 的负载解成 Core、Matcher 和运行时辅助组件，并核对同一冻结点的哈希、路由与水位。恢复入口随后创建候选运行时、装回各 Lane 状态并激活原生订单簿；解析清单时只使用组件，不启动运行线程。 构造约定：禁止实例化静态组件解析工具。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SectionedCoreSnapshotParser {

    /**
     * 按固定段位置依次解码元数据、来源、幂等结果、Matcher、交易状态、费率、划转和 Lane 清单，检查 Lane 数量、摘要及整体配对，再解码终态保留索引并返回组件。假定外层 Recovery 已确认段结构及 CRC，不在这里重新校验 CRC。
     * @param payloads 已按协议顺序接收完整且通过外层 CRC 的负载数组表
     * @param expectedProductLine 调用方明确要求恢复或检查的产品线
     */
    static Components parse(byte[][] payloads, ProductLine expectedProductLine,
            java.nio.file.Path fundsIndexDirectory,
            com.surprising.aeron.config.LaneTopology deploymentTopology) {
        // 从第一段读取且产品线已核对的内部元数据。
        HeaderManifest manifest = SectionedCoreSnapshotValidation.parseHeader(payloads[0], expectedProductLine);
        // 解码的来源消费水位表，重复来源键会被拒绝。
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = parseSources(payloads[1]);
        // 解码的幂等结果表，按输入条目顺序保存。
        Map<UUID, CommandResultLedger.StoredResult> commandResults = parseResults(payloads[2]);
        // 独立 Matcher 编解码器解出的原生模块与配对元数据。
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(payloads[3], deploymentTopology);
        // 独立交易状态编解码器解出的不可变交易视图。
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(payloads[4], manifest.productLine());
        // 解码后的费率策略表，参与业务哈希配对。
        Map<Long, CoreFeePolicyState> feePolicies = CoreFeePolicySnapshotCodec.decode(payloads[5]);
        // 解码后的待完成划转表，参与业务哈希配对。
        Map<Long, TransferFundsCommand> pendingTransfers = CoreTransferSnapshotCodec.decode(payloads[6]);
        // 总负载段数扣除九个固定段后的账户 Lane 数量。
        int laneSectionCount = payloads.length - SectionedCoreSnapshotCodec.BASE_SECTION_COUNT;
        if (laneSectionCount != matcherSnapshot.topology().accountLaneCount()) {
            throw new ProtocolException("snapshot account lane section count mismatch");
        }
        // 按 Lane 段顺序收集的账户清单，随后验证摘要与恢复边界。
        List<AccountLaneSnapshot> accountLanes = new ArrayList<>(laneSectionCount);
        for (/* 当前账户段、用户条目或来源/结果条目的零基下标。 */ int index = 0; index < laneSectionCount; index++) {
            accountLanes.add(parseAccountLane(payloads[8 + index], matcherSnapshot.topology(), index));
        }
        SectionedCoreSnapshotValidation.validateAccountLanes(manifest, accountLanes);
        SectionedCoreSnapshotValidation.validatePairing(
                manifest, sourceSequences, matcherSnapshot, tradingState, feePolicies, pendingTransfers);
        matcherSnapshot.verifyCoreState(tradingState,
                manifest.appliedCommandCount(), manifest.businessStateHash());
        // 已由外层验证过的页尾 CRC 数值，这里只读入公开清单。
        long checksum = ByteBuffer.wrap(payloads[payloads.length - 1])
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        // 最后解码的终态保留索引，恢复或查看清单的调用方负责结束其生命周期。
        TerminalStateRetention retention = TerminalStateRetention.decode(payloads[7], fundsIndexDirectory);
        return new Components(manifest.productLine(), manifest.appliedCommandCount(),
                commandResults, sourceSequences, matcherSnapshot, tradingState, feePolicies,
                pendingTransfers, retention, accountLanes, manifest, checksum);
    }

    /**
     * 检查最小长度、预期 Lane 号、数量及精确用户字节数，逐用户验证路由后构造账户清单；构造器参数错误转为协议异常，列表顺序/重复等进一步约束由 AccountLaneSnapshot 自身检查。
     * @param payload 当前正在读取的某一段负载字节
     * @param topology 已解析 Matcher 段的固定账户路由
     * @param expectedLaneId 当前账户段在协议顺序中必须匹配的零基 Lane 号
     */
    private static AccountLaneSnapshot parseAccountLane(
            byte[] payload, com.surprising.aeron.config.LaneTopology topology, int expectedLaneId) {
        // 当前账户段的小端读取视图。
        ByteBuffer lane = wrap(payload);
        if (lane.remaining() < Integer.BYTES * 2 + Long.BYTES * 5) {
            throw new ProtocolException("truncated account lane section");
        }
        // 段内携带的账户 Lane 号，须与预期顺序一致。
        int laneId = lane.getInt();
        if (laneId != expectedLaneId) {
            throw new ProtocolException("account lane section route mismatch");
        }
        // 该 Lane 快照的修订号。
        long revision = lane.getLong();
        // 该 Lane 已应用的序号水位。
        long appliedSequence = lane.getLong();
        // 该 Lane 已提交的序号水位。
        long committedSequence = lane.getLong();
        // 该 Lane 保存的局部状态哈希。
        long localStateHash = lane.getLong();
        // 该 Lane 保存的局部资金哈希。
        long localFundsHash = lane.getLong();
        // 当前 Lane 用户数量，有一百万条上限。
        int userCount = readCount(lane, 1_000_000, "account lane user");
        // 以精确乘法算出的用户编号数组字节数，必须恰好等于段余量。
        int userBytes = Math.multiplyExact(userCount, Long.BYTES);
        if (lane.remaining() != userBytes) {
            throw new ProtocolException("invalid account lane user section length");
        }
        // 按输入顺序保存的用户编号列表。
        List<Long> userIds = new ArrayList<>(userCount);
        for (/* 当前账户段、用户条目或来源/结果条目的零基下标。 */ int index = 0; index < userCount; index++) {
            // 当前用户编号，必须按快照拓扑路由到此 Lane。
            long userId = lane.getLong();
            if (topology.accountLaneId(userId) != laneId) {
                throw new ProtocolException("account lane user route mismatch");
            }
            userIds.add(userId);
        }
        try {
            return new AccountLaneSnapshot(laneId, revision, appliedSequence, committedSequence,
                    localStateHash, localFundsHash, userIds);
        } catch (/* 当前解析或候选恢复的运行异常，按所在边界转为协议错误并清理已移交资源。 */ IllegalArgumentException exception) {
            throw new ProtocolException("invalid account lane snapshot: " + exception.getMessage());
        }
    }

    /**
     * 读取有上限的来源数并要求段长度精确匹配，逐项检查来源码、非负水位与重复键，按输入顺序保存。
     * @param payload 当前正在读取的某一段负载字节
     */
    private static Map<TradingCoreRuntime.SourceKey, Long> parseSources(byte[] payload) {
        // 来源水位段的小端读取视图。
        ByteBuffer sources = wrap(payload);
        // 通过上限检查的来源水位条目数。
        int sourceCount = readCount(sources, TradingCoreRuntime.MAX_SOURCE_SEQUENCES, "source sequence");
        if (sources.remaining() != (long) sourceCount * SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH) {
            throw new ProtocolException("invalid snapshot source section length");
        }
        // 解码的来源消费水位表，重复来源键会被拒绝。
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = new LinkedHashMap<>();
        for (/* 当前账户段、用户条目或来源/结果条目的零基下标。 */ int index = 0; index < sourceCount; index++) {
            // 当前条目的来源类型，使用固定 wireCode 解码。
            CommandSource source = CommandSource.fromWireCode(sources.getInt());
            // 当前来源实例编号，与来源类型共同组成键。
            long sourceId = sources.getLong();
            // 当前来源已经消费的非负命令水位。
            long sequence = sources.getLong();
            if (sequence < 0 || sourceSequences.put(new TradingCoreRuntime.SourceKey(source, sourceId), sequence) != null) {
                throw new ProtocolException("invalid snapshot source sequence");
            }
        }
        return sourceSequences;
    }

    /**
     * 读取有上限的幂等结果数，逐条解析并拒绝重复 UUID，完成后要求段内没有尾随垃圾。
     * @param payload 当前正在读取的某一段负载字节
     */
    private static Map<UUID, CommandResultLedger.StoredResult> parseResults(byte[] payload) {
        // 幂等结果段的小端读取视图。
        ByteBuffer results = wrap(payload);
        // 通过上限检查的幂等结果条目数。
        int resultCount = readCount(results, TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS, "result");
        // 解码的幂等结果表，按输入条目顺序保存。
        Map<UUID, CommandResultLedger.StoredResult> commandResults = new LinkedHashMap<>();
        for (/* 当前账户段、用户条目或来源/结果条目的零基下标。 */ int index = 0; index < resultCount; index++) {
            // 当前条目的 UUID 与已解码结果值。
            SnapshotResult result = readResult(results);
            if (commandResults.put(result.commandId(), result.value()) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result");
            }
        }
        requireConsumed(results, "results");
        return commandResults;
    }

    /**
     * 先校验条目声明长度并暂时收紧 ByteBuffer limit，再读取 UUID、指纹、状态码、水位及响应字节；要求保留序号为正，响应长度恰好占满条目。成功后恢复原 limit，异常时整次解析失败。
     * @param source 当前幂等结果段的可推进小端读取缓冲
     */
    private static SnapshotResult readResult(ByteBuffer source) {
        if (source.remaining() < Integer.BYTES) throw new ProtocolException("truncated snapshot command result");
        // 该结果条目声明的负载长度，不包含前置四字节长度字段。
        int encodedLength = source.getInt();
        if (encodedLength < SectionedCoreSnapshotCodec.RESULT_FIXED_LENGTH || encodedLength > source.remaining()) {
            throw new ProtocolException("invalid snapshot command result length");
        }
        // 进入当前结果条目前的原缓冲 limit，成功读完后恢复。
        int limit = source.limit();
        source.limit(source.position() + encodedLength);
        // 当前幂等结果对应的原命令 UUID。
        UUID commandId = new UUID(source.getLong(), source.getLong());
        // 按固定长度读取的原命令指纹字节。
        byte[] fingerprint = new byte[CommandFingerprint.LENGTH];
        source.get(fingerprint);
        // 固定线码解出的原命令响应状态。
        ResponseStatus status = ResponseStatus.fromWireCode(source.getInt());
        // 固定线码解出的原业务结果码。
        CoreResultCode resultCode = CoreResultCode.fromWireCode(source.getInt());
        // 原结果记录的 Core 已应用数量，要求非负。
        long appliedCommandCount = source.getLong();
        // 用于终态保留顺序的原结果序号，要求为正。
        long retentionSequence = source.getLong();
        // 原结果响应负载长度，必须与条目剩余字节完全相等。
        int responseLength = source.getInt();
        if (appliedCommandCount < 0 || retentionSequence <= 0
                || responseLength < 0 || responseLength != source.remaining()) {
            throw new ProtocolException("invalid snapshot command result metadata");
        }
        // 按条目长度复制出的原响应负载。
        byte[] responseData = new byte[responseLength];
        source.get(responseData);
        source.limit(limit);
        return new SnapshotResult(commandId, new CommandResultLedger.StoredResult(
                CommandFingerprint.fromBytes(fingerprint), status, resultCode, appliedCommandCount,
                responseData, retentionSequence));
    }

    /**
     * 要求至少四字节，读取非负且不超过当前用途上限的条目数量，错误消息带上用途名称。
     * @param buffer 当前段的小端读取缓冲
     * @param maximum 当前用途允许的最大条目数
     * @param label 数量检查失败时展示的用途名称
     */
    private static int readCount(ByteBuffer buffer, int maximum, String label) {
        if (buffer.remaining() < Integer.BYTES) {
            throw new ProtocolException("truncated snapshot " + label + " count");
        }
        // 当前用途读取的非负且受上限约束的条目数。
        int count = buffer.getInt();
        if (count < 0 || count > maximum) {
            throw new ProtocolException("invalid snapshot " + label + " count");
        }
        return count;
    }

    /**
     * 为当前负载建立小端 ByteBuffer 读取视图，不复制数组。
     * @param payload 当前正在读取的某一段负载字节
     */
    private static ByteBuffer wrap(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * 段内仍有未读取字节时明确拒绝尾随垃圾，防止只解析合法前缀。
     * @param buffer 当前段的小端读取缓冲
     * @param section 要求读尽的段名称
     */
    private static void requireConsumed(ByteBuffer buffer, String section) {
        if (buffer.hasRemaining()) {
            throw new ProtocolException("snapshot " + section + " section has trailing garbage");
        }
    }

    /**
     * 解析一条幂等记录时使用的 UUID 与结果值配对，不是新执行业务产生的响应。
     * @param commandId 当前结果对应的原命令 UUID
     * @param value 已解码的幂等命令结果
     */
    private record SnapshotResult(UUID commandId, CommandResultLedger.StoredResult value) {
    }

    /**
     * 已经解码并完成组件配对的输入集合。保留状态与原生模块引用以及待移交的终态索引，可用于查看清单或准备、激活恢复运行时；生命周期由使用方负责。
     * @param productLine 该快照唯一所属的产品线
     * @param appliedCommandCount 冻结点已应用的 Core 命令水位
     * @param commandResults 按命令 UUID 保存的幂等结果表
     * @param sourceSequences 按来源类型及来源实例记录的消费水位表
     * @param matcherSnapshot 同一产品线与 Core 冻结点的原生撮合快照
     * @param tradingState 冻结点物化的交易状态，包含订单、账户与资金数据
     * @param feePolicies 需要随快照保存的费率策略表
     * @param pendingTransfers 需要随快照保存的待完成划转表
     * @param retention 已解码的终态保留索引，恢复成功后所有权交给候选运行时
     * @param accountLanes 同一冻结点各账户 Lane 的序号、哈希和用户清单
     * @param manifest 第一段解码的内部头部清单
     * @param checksum 快照封套及非页尾段的 CRC32C 数值，以 long 承载
     */
    record Components(
            ProductLine productLine,
            long appliedCommandCount,
            Map<UUID, CommandResultLedger.StoredResult> commandResults,
            Map<TradingCoreRuntime.SourceKey, Long> sourceSequences,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, CoreFeePolicyState> feePolicies,
            Map<Long, TransferFundsCommand> pendingTransfers,
            TerminalStateRetention retention,
            List<AccountLaneSnapshot> accountLanes,
            HeaderManifest manifest,
            long checksum) {

        /**
         * 核对产品线和账户 Lane 清单与 Core 状态配对，准备尚未激活的候选运行时，恢复 Lane 元数据后激活订单簿并返回；运行异常关闭候选或未移交的终态索引，关闭失败附加为 suppressed，再包装协议异常。
         * @param expectedProductLine 调用方明确要求恢复或检查的产品线
         */
        TradingCoreRuntime restore(ProductLine expectedProductLine,
                com.surprising.aeron.config.CoreRuntimeConfiguration configuration, boolean activate) {
            requireProductLine(expectedProductLine);
            // 尚在准备或已经激活的候选运行时，失败时尝试关闭。
            TradingCoreRuntime candidate = null;
            try {
                com.surprising.aeron.service.core.TradingAccountRuntime.validateAccountLaneSnapshotManifest(
                        accountLanes, manifest.appliedCommandCount(), tradingState, matcherSnapshot.topology());
                candidate = TradingCoreRuntime.prepareRestore(productLine, appliedCommandCount,
                        commandResults, sourceSequences, tradingState, retention, matcherSnapshot,
                        manifest.projectionSequence(), feePolicies, pendingTransfers, configuration);
                candidate.restoreAccountLaneSnapshots(accountLanes, manifest.appliedCommandCount());
                if (activate) candidate.activate();
                return candidate;
            } catch (/* 当前解析或候选恢复的运行异常，按所在边界转为协议错误并清理已移交资源。 */ RuntimeException exception) {
                if (candidate == null) retention.close();
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (/* 清理候选运行时的运行异常，附加到原恢复失败。 */ RuntimeException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                }
                throw new com.surprising.aeron.protocol.ProtocolException(
                        "invalid restored snapshot state: " + exception.getMessage(), exception);
            }
        }

        /**
         * 再次核对预期产品线后，从已解析元数据和 Matcher 构造公开清单，不创建运行时，也不计算新 CRC。
         * @param expectedProductLine 调用方明确要求恢复或检查的产品线
         */
        CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            requireProductLine(expectedProductLine);
            return new CoreSnapshotManifest(productLine, SectionedCoreSnapshotCodec.VERSION,
                    manifest.snapshotId(), manifest.clusterTimestamp(),
                    manifest.clusterPosition(), appliedCommandCount, matcherSnapshot.matcherSequence(),
                    manifest.businessStateHash(), matcherSnapshot.engineStateHash(),
                    manifest.sourceSequenceDigest(), matcherSnapshot.topology(), checksum);
        }

        /**
         * 要求当前组件的产品线与调用方预期完全相同，包含预期为空的情况都会拒绝。
         * @param expectedProductLine 调用方明确要求恢复或检查的产品线
         */
        private void requireProductLine(ProductLine expectedProductLine) {
            if (productLine != expectedProductLine) {
                throw new ProtocolException("snapshot product line mismatch: " + productLine);
            }
        }
    }
}
