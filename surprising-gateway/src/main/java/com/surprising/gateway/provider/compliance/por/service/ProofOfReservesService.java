package com.surprising.gateway.provider.compliance.por.service;

import com.surprising.gateway.provider.compliance.por.model.PorModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProofOfReservesService {
    private static final Logger log = LoggerFactory.getLogger(ProofOfReservesService.class);

    private final Map<String, PorSnapshotSummary> snapshots = new ConcurrentHashMap<>();
    private final Map<Long, UserPorProofResponse> userProofs = new ConcurrentHashMap<>();
    private volatile String latestSnapshotId;

    public ProofOfReservesService() {
        // 初始化一个基线储备证明快照
        Map<Long, Long> baselineUsers = Map.of(
                1001L, 150_000L,
                1002L, 250_000L,
                1003L, 500_000L,
                1004L, 100_000L
        );
        generateSnapshot(baselineUsers, 1_100_000L); // 100万负债，110万链上储备，储备率 110%
    }

    /**
     * 根据全站用户权益和链上储备余额生成最新 Merkle 储备证明快照。
     */
    public synchronized PorSnapshotSummary generateSnapshot(Map<Long, Long> userBalancesUsd, long totalReserveUsd) {
        if (userBalancesUsd == null || userBalancesUsd.isEmpty()) {
            throw new IllegalArgumentException("user balances cannot be empty");
        }

        String snapshotId = "por-" + System.currentTimeMillis();
        Instant now = Instant.now();

        List<Long> userIds = new ArrayList<>(userBalancesUsd.keySet());
        Collections.sort(userIds);

        long totalLiability = userBalancesUsd.values().stream().mapToLong(Long::longValue).sum();
        long ratioPpm = totalLiability > 0 ? (totalReserveUsd * 1_000_000L) / totalLiability : 1_000_000L;

        // 构建叶子哈希和私有盐值
        Map<Long, String> userNonces = new HashMap<>();
        List<String> leafHashes = new ArrayList<>();

        for (Long uid : userIds) {
            String nonce = UUID.randomUUID().toString().substring(0, 8);
            userNonces.put(uid, nonce);
            long balance = userBalancesUsd.get(uid);
            leafHashes.add(hashLeaf(uid, nonce, balance));
        }

        // 构建完整的 Merkle 树层级
        List<List<String>> treeLayers = new ArrayList<>();
        treeLayers.add(leafHashes);

        List<String> currentLayer = leafHashes;
        while (currentLayer.size() > 1) {
            List<String> nextLayer = new ArrayList<>();
            for (int i = 0; i < currentLayer.size(); i += 2) {
                String left = currentLayer.get(i);
                String right = (i + 1 < currentLayer.size()) ? currentLayer.get(i + 1) : left;
                nextLayer.add(hashPair(left, right));
            }
            treeLayers.add(nextLayer);
            currentLayer = nextLayer;
        }

        String rootHash = currentLayer.getFirst();

        // 提取每个用户的审计证明路径 (Proof Path)
        for (int i = 0; i < userIds.size(); i++) {
            Long uid = userIds.get(i);
            long balance = userBalancesUsd.get(uid);
            String nonce = userNonces.get(uid);
            List<MerkleProofStep> path = new ArrayList<>();

            int index = i;
            for (int layerIdx = 0; layerIdx < treeLayers.size() - 1; layerIdx++) {
                List<String> layer = treeLayers.get(layerIdx);
                boolean isRight = (index % 2 == 1);
                int siblingIndex = isRight ? index - 1 : index + 1;
                String siblingHash = siblingIndex < layer.size() ? layer.get(siblingIndex) : layer.get(index);
                path.add(new MerkleProofStep(siblingHash, !isRight));
                index /= 2;
            }

            userProofs.put(uid, new UserPorProofResponse(uid, snapshotId, rootHash, balance, nonce, path, now));
        }

        PorSnapshotSummary summary = new PorSnapshotSummary(
                snapshotId, rootHash, totalLiability, totalReserveUsd, ratioPpm, userIds.size(), now
        );
        snapshots.put(snapshotId, summary);
        latestSnapshotId = snapshotId;

        log.info("PoR snapshot created: id={}, root={}, liability={}, reserve={}, ratio={}%",
                snapshotId, rootHash, totalLiability, totalReserveUsd, ratioPpm / 10000.0);
        return summary;
    }

    public PorSnapshotSummary getLatestSummary() {
        return snapshots.get(latestSnapshotId);
    }

    public UserPorProofResponse getUserProof(long userId) {
        UserPorProofResponse proof = userProofs.get(userId);
        if (proof == null) {
            throw new NoSuchElementException("PoR proof not found for user: " + userId);
        }
        return proof;
    }

    /**
     * 独立校验指定叶子和路径是否可还原根哈希。
     */
    public VerifyPorProofResponse verifyProof(VerifyPorProofRequest request) {
        if (request == null || request.proofPath() == null || request.rootHash() == null) {
            return new VerifyPorProofResponse(false, "", "");
        }

        String currentHash = hashLeaf(request.userId(), request.userNonce(), request.userLiabilityUsd());
        for (MerkleProofStep step : request.proofPath()) {
            if (step.isRightSibling()) {
                currentHash = hashPair(currentHash, step.siblingHash());
            } else {
                currentHash = hashPair(step.siblingHash(), currentHash);
            }
        }

        boolean valid = request.rootHash().equalsIgnoreCase(currentHash);
        return new VerifyPorProofResponse(valid, currentHash, request.rootHash());
    }

    public static String hashLeaf(long userId, String nonce, long balanceUsd) {
        return sha256("leaf:" + userId + ":" + nonce + ":" + balanceUsd);
    }

    public static String hashPair(String left, String right) {
        return sha256(left + right);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
