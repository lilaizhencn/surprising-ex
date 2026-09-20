package com.surprising.aeron.service.orchestration;



import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.orchestration.snapshot.CoreSnapshotManifest;
import com.surprising.aeron.service.orchestration.snapshot.SectionedCoreSnapshotCodec;
import com.surprising.aeron.service.orchestration.SectionedCoreSnapshotValidation.HeaderManifest;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshotCodec;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.snapshot.TradingStateSnapshotCodec;
import com.surprising.aeron.service.state.snapshot.CoreFeePolicySnapshotCodec;
import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import com.surprising.aeron.service.state.snapshot.CoreTransferSnapshotCodec;
import com.surprising.aeron.service.state.account.TransferRuntime;
import com.surprising.aeron.service.state.AccountLaneSnapshot;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SectionedCoreSnapshotParser {


    private SectionedCoreSnapshotParser() {
    }

    static Components parse(byte[][] payloads, ProductLine expectedProductLine) {
        HeaderManifest manifest = SectionedCoreSnapshotValidation.parseHeader(payloads[0], expectedProductLine);
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = parseSources(payloads[1]);
        Map<UUID, CommandResultLedger.StoredResult> commandResults = parseResults(payloads[2]);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(payloads[3]);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(payloads[4], manifest.productLine());
        Map<Long, CoreFeePolicyState> feePolicies = CoreFeePolicySnapshotCodec.decode(payloads[5]);
        Map<Long, TransferRuntime> pendingTransfers = CoreTransferSnapshotCodec.decode(payloads[6]);
        TerminalStateRetention retention = TerminalStateRetention.decode(payloads[7]);
        int laneSectionCount = payloads.length - SectionedCoreSnapshotCodec.BASE_SECTION_COUNT;
        if (laneSectionCount != manifest.topology().accountLaneCount()) {
            throw new ProtocolException("snapshot account lane section count mismatch");
        }
        List<AccountLaneSnapshot> accountLanes = new ArrayList<>(laneSectionCount);
        for (int index = 0; index < laneSectionCount; index++) {
            accountLanes.add(parseAccountLane(payloads[8 + index], manifest, index));
        }
        SectionedCoreSnapshotValidation.validateAccountLanes(manifest, accountLanes);
        SectionedCoreSnapshotValidation.validatePairing(
                manifest, sourceSequences, matcherSnapshot, tradingState, feePolicies, pendingTransfers);
        CoreSnapshotImage.verifyMatcherState(matcherSnapshot, tradingState,
                manifest.appliedCommandCount(), manifest.businessStateHash());
        long checksum = ByteBuffer.wrap(payloads[payloads.length - 1])
                .order(ByteOrder.LITTLE_ENDIAN).getLong();
        return new Components(manifest.productLine(), manifest.appliedCommandCount(), manifest.probeValue(),
                commandResults, sourceSequences, matcherSnapshot, tradingState, feePolicies,
                pendingTransfers, retention, accountLanes, manifest, checksum);
    }

    private static AccountLaneSnapshot parseAccountLane(
            byte[] payload, HeaderManifest manifest, int expectedLaneId) {
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
            if (manifest.topology().accountLaneId(userId) != laneId) {
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

    private static Map<TradingCoreRuntime.SourceKey, Long> parseSources(byte[] payload) {
        ByteBuffer sources = wrap(payload);
        int sourceCount = readCount(sources, TradingCoreRuntime.MAX_SOURCE_SEQUENCES, "source sequence");
        if (sources.remaining() != (long) sourceCount * SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH) {
            throw new ProtocolException("invalid snapshot source section length");
        }
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = new LinkedHashMap<>();
        for (int index = 0; index < sourceCount; index++) {
            CommandSource source = CommandSource.fromWireCode(sources.getInt());
            if (sources.getInt() != 0) throw new ProtocolException("invalid snapshot source reserved field");
            long sourceId = sources.getLong();
            long sequence = sources.getLong();
            if (sequence < 0 || sourceSequences.put(new TradingCoreRuntime.SourceKey(source, sourceId), sequence) != null) {
                throw new ProtocolException("invalid snapshot source sequence");
            }
        }
        return sourceSequences;
    }

    private static Map<UUID, CommandResultLedger.StoredResult> parseResults(byte[] payload) {
        ByteBuffer results = wrap(payload);
        int resultCount = readCount(results, TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS, "result");
        Map<UUID, CommandResultLedger.StoredResult> commandResults = new LinkedHashMap<>();
        for (int index = 0; index < resultCount; index++) {
            SnapshotResult result = readResult(results);
            if (commandResults.put(result.commandId(), result.value()) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result");
            }
        }
        requireConsumed(results, "results");
        return commandResults;
    }

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
        return new SnapshotResult(commandId, new CommandResultLedger.StoredResult(
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

    private static void requireConsumed(ByteBuffer buffer, String section) {
        if (buffer.hasRemaining()) {
            throw new ProtocolException("snapshot " + section + " section has trailing garbage");
        }
    }

    private record SnapshotResult(UUID commandId, CommandResultLedger.StoredResult value) {
    }

    record Components(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CommandResultLedger.StoredResult> commandResults,
            Map<TradingCoreRuntime.SourceKey, Long> sourceSequences,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, CoreFeePolicyState> feePolicies,
            Map<Long, TransferRuntime> pendingTransfers,
            TerminalStateRetention retention,
            List<AccountLaneSnapshot> accountLanes,
            HeaderManifest manifest,
            long checksum) {

        TradingCoreRuntime restore(ProductLine expectedProductLine) {
            requireProductLine(expectedProductLine);
            TradingCoreRuntime candidate = null;
            try {
                com.surprising.aeron.service.state.TradingRuntimeState.validateAccountLaneSnapshotManifest(
                        accountLanes, manifest.coreSequence(), tradingState, manifest.topology());
                candidate = TradingCoreRuntime.prepareRestore(productLine, appliedCommandCount, probeValue,
                        commandResults, sourceSequences, tradingState, retention, matcherSnapshot,
                        manifest.projectionSequence(), feePolicies, pendingTransfers,
                        manifest.auditBusinessStateHash(), manifest.auditFundsStateHash());
                candidate.restoreAccountLaneSnapshots(accountLanes, manifest.coreSequence());
                candidate.activate();
                return candidate;
            } catch (RuntimeException exception) {
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

        CoreSnapshotManifest manifest(ProductLine expectedProductLine) {
            requireProductLine(expectedProductLine);
            return new CoreSnapshotManifest(productLine, SectionedCoreSnapshotCodec.VERSION,
                    matcherSnapshot.coreShardId(), manifest.routeVersion(), manifest.snapshotId(),
                    manifest.coreSequence(), manifest.clusterTimestamp(), manifest.clusterPosition(),
                    appliedCommandCount, manifest.matcherSequence(), manifest.businessStateHash(),
                    manifest.engineStateHash(), manifest.bookStateHash(), manifest.symbolRegistryHash(),
                    manifest.userRegistryHash(), manifest.activeOrderHash(),
                    manifest.sourceSequenceDigest(), manifest.forkGitSha(), manifest.artifactSha256(),
                    manifest.matcherConfigHash(), manifest.topology(), manifest.topologyHash(),
                    manifest.symbolRouteHash(), manifest.globalFundsHash(), checksum);
        }

        private void requireProductLine(ProductLine expectedProductLine) {
            if (productLine != expectedProductLine) {
                throw new ProtocolException("snapshot product line mismatch: " + productLine);
            }
        }
    }
}
