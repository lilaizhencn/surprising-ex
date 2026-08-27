package com.surprising.aeron.service;

import com.surprising.aeron.protocol.ProductLineWireCode;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class SectionedCoreSnapshotValidation {

    private SectionedCoreSnapshotValidation() {
    }

    static HeaderManifest parseHeader(byte[] encoded, ProductLine expectedProductLine) {
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        ProductLine productLine = ProductLineWireCode.decode(Byte.toUnsignedInt(header.get()));
        if (productLine != expectedProductLine) {
            throw new ProtocolException("snapshot product line mismatch: " + productLine);
        }
        int shardCode = Byte.toUnsignedInt(header.get());
        int routeVersion = header.getInt();
        LaneTopology topology;
        try {
            topology = new LaneTopology(routeVersion, header.getInt(), header.getInt(), header.getInt(),
                    header.getInt(), header.getLong(), header.getInt(), header.getInt(), header.getInt());
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("snapshot route mismatch: " + exception.getMessage());
        }
        long topologyHash = header.getLong();
        long symbolRouteHash = header.getLong();
        long appliedCommandCount = header.getLong();
        long probeValue = header.getLong();
        long snapshotId = header.getLong();
        long coreSequence = header.getLong();
        long clusterTimestamp = header.getLong();
        long clusterPosition = header.getLong();
        long matcherSequence = header.getLong();
        long businessStateHash = header.getLong();
        long globalFundsHash = header.getLong();
        int engineStateHash = header.getInt();
        int bookStateHash = header.getInt();
        long symbolRegistryHash = header.getLong();
        long userRegistryHash = header.getLong();
        long instrumentRegistryHash = header.getLong();
        long activeOrderHash = header.getLong();
        long sourceSequenceDigest = header.getLong();
        long outboxAcknowledgedSequence = header.getLong();
        long outboxNextSequence = header.getLong();
        int outboxPendingCount = header.getInt();
        long outboxPendingDigest = header.getLong();
        long matcherConfigHash = header.getLong();
        String forkGitSha = readFixedAscii(header, SectionedCoreSnapshotCodec.FORK_GIT_SHA_LENGTH);
        String artifactSha256 = readFixedAscii(header, SectionedCoreSnapshotCodec.ARTIFACT_SHA256_LENGTH);
        if (header.hasRemaining()) throw new ProtocolException("snapshot header section has trailing garbage");
        if (shardCode != 0) throw new ProtocolException("snapshot core shard mismatch");
        if (routeVersion != MatcherSnapshot.ROUTE_VERSION) {
            throw new ProtocolException("snapshot route mismatch");
        }
        if (topologyHash != topology.topologyHash()) {
            throw new ProtocolException("snapshot topology mismatch");
        }
        if (snapshotId <= 0) throw new ProtocolException("snapshot id mismatch");
        if (appliedCommandCount < 0 || coreSequence < 0 || matcherSequence < 0
                || clusterTimestamp < 0 || clusterPosition < 0) {
            throw new ProtocolException("invalid snapshot sequence or position");
        }
        if (outboxAcknowledgedSequence < 0 || outboxNextSequence <= outboxAcknowledgedSequence
                || outboxPendingCount < 0 || outboxPendingCount > CoreExportState.MAX_PENDING_EVENTS) {
            throw new ProtocolException("invalid snapshot outbox metadata");
        }
        return new HeaderManifest(productLine, routeVersion, topology, topologyHash, symbolRouteHash,
                snapshotId, coreSequence,
                clusterTimestamp, clusterPosition, appliedCommandCount, probeValue, matcherSequence,
                businessStateHash, globalFundsHash, engineStateHash, bookStateHash,
                symbolRegistryHash, userRegistryHash,
                instrumentRegistryHash, activeOrderHash, sourceSequenceDigest, outboxAcknowledgedSequence,
                outboxNextSequence, outboxPendingCount, outboxPendingDigest, forkGitSha, artifactSha256,
                matcherConfigHash);
    }

    static void validatePairing(
            HeaderManifest manifest,
            Map<CoreProbeState.SourceKey, Long> sourceSequences,
            CoreExportState exportState,
            MatcherSnapshot matcherSnapshot,
            TradingCoreState tradingState) {
        requireMatch(manifest.productLine() == matcherSnapshot.productLine()
                && manifest.productLine() == tradingState.productLine(), "product line");
        requireMatch(manifest.routeVersion() == matcherSnapshot.routeVersion(), "route");
        requireMatch(manifest.topology().equals(matcherSnapshot.topology())
                && manifest.topologyHash() == matcherSnapshot.topologyHash(), "topology");
        requireMatch(manifest.symbolRouteHash() == matcherSnapshot.symbolRouteHash(), "symbol route");
        requireMatch(manifest.snapshotId() == matcherSnapshot.snapshotId(), "snapshot id");
        requireMatch(manifest.coreSequence() == matcherSnapshot.coreSequence(), "core sequence");
        requireMatch(manifest.appliedCommandCount() == manifest.coreSequence(), "applied sequence");
        requireMatch(manifest.matcherSequence() == matcherSnapshot.matcherSequence(), "matcher sequence");
        requireMatch(manifest.businessStateHash() == tradingState.businessStateHash()
                && manifest.businessStateHash() == matcherSnapshot.coreBusinessStateHash(), "business state hash");
        requireMatch(manifest.globalFundsHash()
                == com.surprising.aeron.service.state.RollingFundsStateHash.compute(tradingState), "funds hash");
        requireMatch(manifest.engineStateHash() == matcherSnapshot.engineStateHash(), "engine state hash");
        requireMatch(manifest.bookStateHash() == matcherSnapshot.bookStateHash(), "book state hash");
        requireMatch(manifest.symbolRegistryHash() == matcherSnapshot.symbolRegistryHash(), "symbol registry hash");
        requireMatch(manifest.userRegistryHash() == matcherSnapshot.userRegistryHash(), "user registry hash");
        requireMatch(manifest.instrumentRegistryHash() == matcherSnapshot.instrumentRegistryHash()
                && manifest.instrumentRegistryHash() == MatcherSnapshot.instrumentRegistryHash(tradingState),
                "instrument registry hash");
        requireMatch(manifest.activeOrderHash() == matcherSnapshot.activeOrderHash()
                && manifest.activeOrderHash() == MatcherSnapshot.activeOrderHash(tradingState), "active order hash");
        requireMatch(manifest.sourceSequenceDigest() == CoreProbeState.sourceSequenceDigest(sourceSequences),
                "source sequence digest");
        requireMatch(manifest.outboxAcknowledgedSequence() == exportState.acknowledgedSequence(),
                "outbox acknowledged sequence");
        requireMatch(manifest.outboxNextSequence() == exportState.nextSequence(), "outbox next sequence");
        requireMatch(manifest.outboxPendingCount() == exportState.pendingCount(), "outbox pending count");
        requireMatch(manifest.outboxPendingDigest() == exportState.pendingDigest(), "outbox pending digest");
        requireMatch(manifest.matcherConfigHash() == matcherSnapshot.matcherConfigHash(), "matcher config");
        requireMatch(manifest.forkGitSha().equals(matcherSnapshot.forkGitSha()), "fork identity");
        requireMatch(manifest.artifactSha256().equals(matcherSnapshot.artifactSha256()), "artifact identity");
    }

    private static String readFixedAscii(ByteBuffer buffer, int length) {
        byte[] encoded = new byte[length];
        buffer.get(encoded);
        return new String(encoded, StandardCharsets.US_ASCII);
    }

    private static void requireMatch(boolean matches, String field) {
        if (!matches) throw new ProtocolException("snapshot " + field + " mismatch");
    }

    record HeaderManifest(
            ProductLine productLine, int routeVersion, LaneTopology topology, long topologyHash,
            long symbolRouteHash, long snapshotId, long coreSequence,
            long clusterTimestamp, long clusterPosition, long appliedCommandCount, long probeValue,
            long matcherSequence, long businessStateHash, long globalFundsHash,
            int engineStateHash, int bookStateHash,
            long symbolRegistryHash, long userRegistryHash, long instrumentRegistryHash, long activeOrderHash,
            long sourceSequenceDigest, long outboxAcknowledgedSequence, long outboxNextSequence,
            int outboxPendingCount, long outboxPendingDigest, String forkGitSha, String artifactSha256,
            long matcherConfigHash) {
    }
}
