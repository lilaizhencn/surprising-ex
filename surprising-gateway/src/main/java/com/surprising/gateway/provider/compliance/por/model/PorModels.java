package com.surprising.gateway.provider.compliance.por.model;

import java.time.Instant;
import java.util.List;

public final class PorModels {
    private PorModels() {}

    public record PorSnapshotSummary(
            String snapshotId,
            String rootHash,
            long totalLiabilityUsd,
            long totalReserveUsd,
            long reserveRatioPpm, // 1000000 = 100%
            int totalUserCount,
            Instant snapshotTime
    ) {}

    public record MerkleProofStep(
            String siblingHash,
            boolean isRightSibling
    ) {}

    public record UserPorProofResponse(
            long userId,
            String snapshotId,
            String rootHash,
            long userLiabilityUsd,
            String userNonce,
            List<MerkleProofStep> proofPath,
            Instant snapshotTime
    ) {}

    public record VerifyPorProofRequest(
            String rootHash,
            long userId,
            long userLiabilityUsd,
            String userNonce,
            List<MerkleProofStep> proofPath
    ) {}

    public record VerifyPorProofResponse(
            boolean valid,
            String computedRootHash,
            String expectedRootHash
    ) {}
}
