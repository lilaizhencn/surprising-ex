package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.List;
import com.surprising.aeron.service.state.AccountLaneSnapshot;

final class SectionedCoreSnapshotValidation {

    private SectionedCoreSnapshotValidation() {
    }

    static HeaderManifest parseHeader(byte[] encoded, ProductLine expectedProductLine) {
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(header.get()));
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        LaneTopology topology;
        try {
            topology = new LaneTopology(LaneTopology.ROUTE_VERSION,
                    header.getInt(), header.getInt(), header.getInt(),
                    header.getInt(), header.getLong(), header.getInt(), header.getInt(), header.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("snapshot route mismatch: " + exception.getMessage());
        }
        long appliedCommandCount = header.getLong();
        long probeValue = header.getLong();
        long snapshotId = header.getLong();
        long coreSequence = header.getLong();
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
        if (appliedCommandCount < 0 || coreSequence < 0 || projectionSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new ProtocolException("invalid snapshot sequence or position");
        }
        return new HeaderManifest(productLine, topology, snapshotId, coreSequence,
                projectionSequence, accountLaneDigest,
                clusterTimestamp, clusterPosition, appliedCommandCount, probeValue,
                businessStateHash, globalFundsHash, sourceSequenceDigest);
    }

    static void validatePairing(
            HeaderManifest manifest,
            Map<TradingCoreRuntime.SourceKey, Long> sourceSequences,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> feePolicies,
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> pendingTransfers,
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
                == com.surprising.aeron.service.state.FundsStateHash.compute(tradingState), "funds hash");
        requireMatch(manifest.sourceSequenceDigest() == TradingCoreRuntime.sourceSequenceDigest(sourceSequences),
                "source sequence digest");
    }

    /** 冻结的划转 v1 哈希；校验旧数据后才允许补齐用户字段，不能跳过原始哈希校验。 */
    private static long implicitUserBusinessHash(long base,
            Map<Long, com.surprising.aeron.service.state.model.CoreFeePolicyState> policies,
            Map<Long, com.surprising.aeron.service.state.account.TransferRuntime> transfers) {
        base = TradingCoreRuntime.canonicalBusinessStateHash(base, policies, Map.of());
        if (transfers.isEmpty()) return base;
        long digest = TradingCoreRuntime.HASH_OFFSET_BASIS;
        for (var transfer : new java.util.TreeMap<>(transfers).values()) {
            var command = transfer.command();
            digest = TradingCoreRuntime.mix(digest, transfer.userId());
            digest = TradingCoreRuntime.mix(digest, command.transferId());
            digest = TradingCoreRuntime.mix(digest, com.surprising.aeron.service.state.snapshot.SnapshotEnumCodes.encode(command.sourceProductLine()));
            digest = TradingCoreRuntime.mix(digest, com.surprising.aeron.service.state.snapshot.SnapshotEnumCodes.encode(command.targetProductLine()));
            digest = TradingCoreRuntime.mixText(digest, command.sourceAccountType());
            digest = TradingCoreRuntime.mixText(digest, command.targetAccountType());
            digest = TradingCoreRuntime.mixText(digest, command.asset());
            digest = TradingCoreRuntime.mix(digest, command.amountUnits());
            digest = TradingCoreRuntime.mixText(digest, command.referenceId());
            digest = TradingCoreRuntime.mixText(digest, command.reason());
        }
        return TradingCoreRuntime.mix(base, digest);
    }

    static void validateAccountLanes(HeaderManifest manifest, List<AccountLaneSnapshot> lanes) {
        requireMatch(manifest.accountLaneDigest() == accountLaneDigest(lanes), "account lane hash");
    }

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

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static void requireMatch(boolean matches, String field) {
        if (!matches) throw new ProtocolException("snapshot " + field + " mismatch");
    }

    record HeaderManifest(
            ProductLine productLine, LaneTopology topology, long snapshotId, long coreSequence,
            long projectionSequence, long accountLaneDigest,
            long clusterTimestamp, long clusterPosition, long appliedCommandCount, long probeValue,
            long businessStateHash, long globalFundsHash,
            long sourceSequenceDigest) {
    }
}
