package com.surprising.gateway.provider.compliance.por.service;

import com.surprising.gateway.provider.compliance.por.model.PorModels.*;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProofOfReservesServiceTest {

    private final ProofOfReservesService service = new ProofOfReservesService();

    @Test
    @DisplayName("测试生成储备证明快照并正确计算储备金率")
    void shouldGenerateSnapshotWithAccurateRatio() {
        Map<Long, Long> balances = Map.of(
                1001L, 200_000L,
                1002L, 800_000L
        );
        // 总负债 1,000,000 USD，链上储备 1,200,000 USD -> 储备率 120% (1,200,000 ppm)
        PorSnapshotSummary summary = service.generateSnapshot(balances, 1_200_000L);

        assertNotNull(summary);
        assertNotNull(summary.rootHash());
        assertEquals(1_000_000L, summary.totalLiabilityUsd());
        assertEquals(1_200_000L, summary.totalReserveUsd());
        assertEquals(1_200_000L, summary.reserveRatioPpm());
        assertEquals(2, summary.totalUserCount());
    }

    @Test
    @DisplayName("测试用户 Merkle 路径数学验证成功")
    void shouldVerifyValidMerkleProofPath() {
        Map<Long, Long> balances = Map.of(
                1001L, 100_000L,
                1002L, 200_000L,
                1003L, 300_000L,
                1004L, 400_000L
        );
        PorSnapshotSummary summary = service.generateSnapshot(balances, 1_000_000L);

        UserPorProofResponse proof = service.getUserProof(1003L);
        assertNotNull(proof);
        assertEquals(1003L, proof.userId());
        assertEquals(300_000L, proof.userLiabilityUsd());
        assertEquals(summary.rootHash(), proof.rootHash());

        // 验证该证明能够完整反推还原出 rootHash
        VerifyPorProofRequest verifyReq = new VerifyPorProofRequest(
                proof.rootHash(),
                proof.userId(),
                proof.userLiabilityUsd(),
                proof.userNonce(),
                proof.proofPath()
        );
        VerifyPorProofResponse response = service.verifyProof(verifyReq);
        assertTrue(response.valid());
        assertEquals(proof.rootHash(), response.computedRootHash());
    }

    @Test
    @DisplayName("篡改用户负债金额或私有 nonce 时防欺诈校验失败")
    void shouldFailVerificationWhenTampered() {
        Map<Long, Long> balances = Map.of(
                1001L, 100_000L,
                1002L, 200_000L
        );
        PorSnapshotSummary summary = service.generateSnapshot(balances, 500_000L);

        UserPorProofResponse proof = service.getUserProof(1001L);

        // 恶意篡改金额 (100_000 -> 999_999)
        VerifyPorProofRequest tampered = new VerifyPorProofRequest(
                proof.rootHash(),
                proof.userId(),
                999_999L,
                proof.userNonce(),
                proof.proofPath()
        );
        VerifyPorProofResponse response = service.verifyProof(tampered);
        assertFalse(response.valid());
    }
}
