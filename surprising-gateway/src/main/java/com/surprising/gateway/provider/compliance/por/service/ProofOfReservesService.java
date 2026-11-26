package com.surprising.gateway.provider.compliance.por.service;

import com.surprising.gateway.provider.compliance.por.model.PorModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class ProofOfReservesService {
    private static final Logger log = LoggerFactory.getLogger(ProofOfReservesService.class);

    private final Map<String, PorSnapshotSummary> snapshots = new ConcurrentHashMap<>();
    private final Map<Long, UserPorProofResponse> userProofs = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final ReentrantLock snapshotLock = new ReentrantLock();
    private final AtomicLong snapshotSequence = new AtomicLong(0);
    private volatile String latestSnapshotId;

    public ProofOfReservesService() {
        this(null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProofOfReservesService(@org.springframework.beans.factory.annotation.Autowired(required = false) @Nullable JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        recordEmptySnapshot();
    }

    /**
     * 根据全站用户权益和链上储备余额生成最新 Merkle 储备证明快照。
     */
    public PorSnapshotSummary generateSnapshot(Map<Long, Long> userBalancesUsd, long totalReserveUsd) {
        if (userBalancesUsd == null || userBalancesUsd.isEmpty()) {
            throw new IllegalArgumentException("user balances cannot be empty");
        }
        snapshotLock.lock();
        try {
            return generateSnapshotLocked(userBalancesUsd, totalReserveUsd);
        } finally {
            snapshotLock.unlock();
        }
    }

    private PorSnapshotSummary generateSnapshotLocked(Map<Long, Long> userBalancesUsd, long totalReserveUsd) {
        String snapshotId = nextSnapshotId();
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

    public List<PorSnapshotSummary> listSnapshots() {
        return snapshots.values().stream()
                .sorted(Comparator.comparing(PorSnapshotSummary::snapshotTime).reversed()
                        .thenComparing(Comparator.comparing(PorSnapshotSummary::snapshotId).reversed()))
                .toList();
    }

    /**
     * 基于数据库当前资金投影触发一次全新的 PoR 默克尔树快照。
     * 无真实正余额记录时诚实展示 0 值空快照，禁止编造假用户余额或虚假储备率。
     */
    public PorSnapshotSummary triggerSnapshotFromDatabase() {
        return triggerSnapshotFromDatabase(this.jdbcTemplate);
    }

    /**
     * 基于指定 JdbcTemplate 的资金投影触发 PoR 默克尔树快照。
     */
    public PorSnapshotSummary triggerSnapshotFromDatabase(@Nullable JdbcTemplate jdbc) {
        snapshotLock.lock();
        try {
            Map<Long, Long> userBalancesUsd = new LinkedHashMap<>();
            if (jdbc != null) {
                try {
                    String sql = """
                            WITH latest_prod AS (
                                SELECT DISTINCT ON (user_id, account_type, asset) user_id, asset, balance_after_units
                                FROM account_product_ledger_entries
                                ORDER BY user_id, account_type, asset, entry_id DESC
                            ),
                            latest_base AS (
                                SELECT DISTINCT ON (user_id, asset) user_id, asset, balance_after_units
                                FROM account_ledger_entries
                                ORDER BY user_id, asset, entry_id DESC
                            ),
                            combined AS (
                                SELECT user_id, asset, balance_after_units FROM latest_prod WHERE balance_after_units > 0
                                UNION ALL
                                SELECT user_id, asset, balance_after_units FROM latest_base WHERE balance_after_units > 0
                            )
                            SELECT c.user_id,
                                   GREATEST(1, SUM((c.balance_after_units::numeric / NULLIF(a.scale_units, 0)))::bigint) AS total_usd
                            FROM combined c
                            JOIN assets a ON a.asset = c.asset
                            GROUP BY c.user_id
                            """;
                    jdbc.query(sql, rs -> {
                        long uid = rs.getLong("user_id");
                        long bal = rs.getLong("total_usd");
                        if (uid > 0 && bal > 0) {
                            userBalancesUsd.put(uid, bal);
                        }
                    });
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("PoR database ledger query failed: " + ex.getMessage(), ex);
                }
            }
            if (userBalancesUsd.isEmpty()) {
                return recordEmptySnapshot();
            }
            // 未接入真实链上储备对账前不伪造超额储备，储备金额诚实记为 0
            return generateSnapshotLocked(userBalancesUsd, 0L);
        } finally {
            snapshotLock.unlock();
        }
    }

    private PorSnapshotSummary recordEmptySnapshot() {
        String snapshotId = nextSnapshotId();
        Instant now = Instant.now();
        PorSnapshotSummary summary = new PorSnapshotSummary(
                snapshotId, sha256("empty"), 0L, 0L, 0L, 0, now
        );
        userProofs.clear();
        snapshots.put(snapshotId, summary);
        latestSnapshotId = snapshotId;
        return summary;
    }

    private String nextSnapshotId() {
        return "por-" + System.currentTimeMillis() + "-" + snapshotSequence.incrementAndGet();
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
