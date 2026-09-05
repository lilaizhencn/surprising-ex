package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.SectionedCoreSnapshotValidation.HeaderManifest;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.matching.MatcherSnapshotCodec;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingStateSnapshotCodec;
import com.surprising.aeron.service.state.CoreFeePolicySnapshotCodec;
import com.surprising.aeron.service.state.CoreFeePolicyState;
import com.surprising.aeron.service.state.CoreTransferSnapshotCodec;
import com.surprising.aeron.service.state.TransferRuntime;
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

    private static volatile java.util.function.Consumer<CoreProbeState> beforeActivationObserverForTest;

    private SectionedCoreSnapshotParser() {
    }

    static void setBeforeActivationObserverForTest(java.util.function.Consumer<CoreProbeState> observer) {
        beforeActivationObserverForTest = observer;
    }

    static Components parse(byte[][] payloads, ProductLine expectedProductLine) {
        HeaderManifest manifest = SectionedCoreSnapshotValidation.parseHeader(payloads[0], expectedProductLine);
        Map<CoreProbeState.SourceKey, Long> sourceSequences = parseSources(payloads[1]);
        Map<UUID, CoreProbeState.StoredResult> commandResults = parseResults(payloads[2]);
        OutboxSnapshot outbox = parseOutbox(payloads[3]);
        MatcherSnapshot matcherSnapshot = MatcherSnapshotCodec.decode(payloads[4]);
        TradingCoreState tradingState = TradingStateSnapshotCodec.decode(payloads[5], manifest.productLine());
        Map<Long, CoreFeePolicyState> feePolicies = CoreFeePolicySnapshotCodec.decode(payloads[6]);
        Map<Long, TransferRuntime> pendingTransfers = CoreTransferSnapshotCodec.decode(payloads[7]);
        TerminalStateRetention retention = TerminalStateRetention.decode(payloads[8]);
        int laneSectionCount = payloads.length - SectionedCoreSnapshotCodec.BASE_SECTION_COUNT;
        if (laneSectionCount != manifest.topology().accountLaneCount()) {
            throw new ProtocolException("snapshot account lane section count mismatch");
        }
        List<AccountLaneSnapshot> accountLanes = new ArrayList<>(laneSectionCount);
        for (int index = 0; index < laneSectionCount; index++) {
            accountLanes.add(parseAccountLane(payloads[9 + index], manifest));
        }
        SectionedCoreSnapshotValidation.validateAccountLanes(manifest, accountLanes);
        CoreExportState exportState = null;
        try {
            exportState = CoreExportState.restore(expectedProductLine,
                    outbox.acknowledgedSequence(), outbox.nextSequence(), outbox.events(),
                    outbox.reservedLengths());
            SectionedCoreSnapshotValidation.validatePairing(
                    manifest, sourceSequences, exportState, matcherSnapshot, tradingState,
                    feePolicies, pendingTransfers);
            CoreSnapshotImage.verifyMatcherState(matcherSnapshot, tradingState,
                    manifest.appliedCommandCount(), manifest.businessStateHash());
            long checksum = ByteBuffer.wrap(payloads[payloads.length - 1])
                    .order(ByteOrder.LITTLE_ENDIAN).getLong();
            return new Components(manifest.productLine(), manifest.appliedCommandCount(), manifest.probeValue(),
                    commandResults, sourceSequences, exportState, matcherSnapshot, tradingState, feePolicies,
                    pendingTransfers, retention, accountLanes, manifest, checksum);
        } catch (RuntimeException failure) {
            if (exportState != null) exportState.close();
            throw failure;
        }
    }

    private static AccountLaneSnapshot parseAccountLane(byte[] payload, HeaderManifest manifest) {
        ByteBuffer lane = wrap(payload);
        if (lane.remaining() < Integer.BYTES * 2 + Long.BYTES * 5) {
            throw new ProtocolException("truncated account lane section");
        }
        int laneId = lane.getInt();
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

    private static Map<CoreProbeState.SourceKey, Long> parseSources(byte[] payload) {
        ByteBuffer sources = wrap(payload);
        int sourceCount = readCount(sources, CoreProbeState.MAX_SOURCE_SEQUENCES, "source sequence");
        if (sources.remaining() != (long) sourceCount * SectionedCoreSnapshotCodec.SOURCE_SEQUENCE_LENGTH) {
            throw new ProtocolException("invalid snapshot source section length");
        }
        Map<CoreProbeState.SourceKey, Long> sourceSequences = new LinkedHashMap<>();
        for (int index = 0; index < sourceCount; index++) {
            CommandSource source = CommandSource.fromWireCode(sources.getInt());
            if (sources.getInt() != 0) throw new ProtocolException("invalid snapshot source reserved field");
            long sourceId = sources.getLong();
            long sequence = sources.getLong();
            if (sequence < 0 || sourceSequences.put(new CoreProbeState.SourceKey(source, sourceId), sequence) != null) {
                throw new ProtocolException("invalid snapshot source sequence");
            }
        }
        return sourceSequences;
    }

    private static Map<UUID, CoreProbeState.StoredResult> parseResults(byte[] payload) {
        ByteBuffer results = wrap(payload);
        int resultCount = readCount(results, CoreProbeState.MAX_IDEMPOTENCY_RESULTS, "result");
        Map<UUID, CoreProbeState.StoredResult> commandResults = new LinkedHashMap<>();
        for (int index = 0; index < resultCount; index++) {
            SnapshotResult result = readResult(results);
            if (commandResults.put(result.commandId(), result.value()) != null) {
                throw new ProtocolException("invalid duplicate snapshot command result");
            }
        }
        requireConsumed(results, "results");
        return commandResults;
    }

    private static OutboxSnapshot parseOutbox(byte[] payload) {
        ByteBuffer outbox = wrap(payload);
        if (outbox.remaining() < SectionedCoreSnapshotCodec.OUTBOX_FIXED_LENGTH) {
            throw new ProtocolException("truncated snapshot outbox");
        }
        long acknowledgedSequence = outbox.getLong();
        long nextSequence = outbox.getLong();
        int eventCount = readCount(outbox, CoreExportState.MAX_PENDING_EVENTS, "outbox event");
        ArrayList<CoreMessage> events = new ArrayList<>(eventCount);
        ArrayList<Integer> reservedLengths = new ArrayList<>(eventCount);
        for (int index = 0; index < eventCount; index++) {
            if (outbox.remaining() < Integer.BYTES * 2) {
                throw new ProtocolException("truncated snapshot outbox event");
            }
            int reservedLength = outbox.getInt();
            int eventLength = outbox.getInt();
            if (eventLength <= 0 || eventLength > outbox.remaining()
                    || reservedLength < eventLength || reservedLength > CoreExportState.maxReservedEventBytes()) {
                throw new ProtocolException("invalid snapshot outbox event length");
            }
            byte[] event = new byte[eventLength];
            outbox.get(event);
            events.add(CoreMessageCodec.decode(event));
            reservedLengths.add(reservedLength);
        }
        requireConsumed(outbox, "outbox");
        return new OutboxSnapshot(acknowledgedSequence, nextSequence,
                List.copyOf(events), List.copyOf(reservedLengths));
    }

    private static SnapshotResult readResult(ByteBuffer source) {
        if (source.remaining() < Integer.BYTES) throw new ProtocolException("truncated snapshot command result");
        int encodedLength = source.getInt();
        if (encodedLength < CoreStateSnapshotCodec.RESULT_FIXED_LENGTH || encodedLength > source.remaining()) {
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
        long requiredExportSequence = source.getLong();
        long stateHash = source.getLong();
        long retentionSequence = source.getLong();
        int responseLength = source.getInt();
        if (appliedCommandCount < 0 || requiredExportSequence < 0 || retentionSequence <= 0
                || responseLength < 0 || responseLength != source.remaining()) {
            throw new ProtocolException("invalid snapshot command result metadata");
        }
        byte[] responseData = new byte[responseLength];
        source.get(responseData);
        source.limit(limit);
        return new SnapshotResult(commandId, new CoreProbeState.StoredResult(
                CommandFingerprint.fromBytes(fingerprint), status, resultCode, appliedCommandCount,
                requiredExportSequence, stateHash, responseData, retentionSequence));
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

    private record SnapshotResult(UUID commandId, CoreProbeState.StoredResult value) {
    }

    private record OutboxSnapshot(long acknowledgedSequence, long nextSequence, List<CoreMessage> events,
                                  List<Integer> reservedLengths) {
    }

    record Components(
            ProductLine productLine,
            long appliedCommandCount,
            long probeValue,
            Map<UUID, CoreProbeState.StoredResult> commandResults,
            Map<CoreProbeState.SourceKey, Long> sourceSequences,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, CoreFeePolicyState> feePolicies,
            Map<Long, TransferRuntime> pendingTransfers,
            TerminalStateRetention retention,
            List<AccountLaneSnapshot> accountLanes,
            HeaderManifest manifest,
            long checksum) {

        CoreProbeState restore(ProductLine expectedProductLine) {
            requireProductLine(expectedProductLine);
            CoreProbeState candidate = null;
            try {
                com.surprising.aeron.service.state.TradingRuntimeState.validateAccountLaneSnapshotManifest(
                        accountLanes, manifest.projectionSequence(), tradingState, manifest.topology());
                candidate = CoreProbeState.prepareRestore(productLine, appliedCommandCount, probeValue,
                        commandResults, sourceSequences, tradingState, exportState, retention, matcherSnapshot,
                        manifest.projectionSequence(), feePolicies, pendingTransfers,
                        manifest.auditBusinessStateHash(), manifest.auditFundsStateHash());
                candidate.restoreAccountLaneSnapshots(accountLanes, manifest.projectionSequence());
                java.util.function.Consumer<CoreProbeState> observer = beforeActivationObserverForTest;
                if (observer != null) observer.accept(candidate);
                candidate.activate();
                return candidate;
            } catch (RuntimeException exception) {
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (RuntimeException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                } else {
                    try {
                        exportState.close();
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
                    manifest.userRegistryHash(), manifest.instrumentRegistryHash(), manifest.activeOrderHash(),
                    manifest.sourceSequenceDigest(), manifest.forkGitSha(), manifest.artifactSha256(),
                    manifest.matcherConfigHash(), manifest.topology(), manifest.topologyHash(),
                    manifest.symbolRouteHash(), manifest.globalFundsHash(), exportState.status(),
                    manifest.outboxPendingDigest(), checksum);
        }

        private void requireProductLine(ProductLine expectedProductLine) {
            if (productLine != expectedProductLine) {
                throw new ProtocolException("snapshot product line mismatch: " + productLine);
            }
        }
    }
}
