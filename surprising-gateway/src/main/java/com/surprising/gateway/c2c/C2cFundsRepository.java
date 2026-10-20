package com.surprising.gateway.c2c;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久资金意图是重试时唯一的参数来源，不能根据广告或收款账号的新值重新组装。 */
@Repository
public class C2cFundsRepository {
    public record Intent(UUID id, long transferId, String ownerType, UUID ownerId, String purpose,
                         long sourceUserId, long targetUserId, String asset, long amountUnits, String status) {
        public boolean terminal() { return status.equals("APPLIED") || status.equals("REJECTED"); }
    }
    private final JdbcTemplate jdbc;
    public C2cFundsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Intent createOrGet(String ownerType, UUID ownerId, String purpose,
                       long sourceUserId, long targetUserId, String asset, long units) {
        if (ownerId == null || sourceUserId <= 0 || targetUserId <= 0 || sourceUserId == targetUserId || units <= 0
                || asset == null || !asset.matches("[A-Z0-9]{2,20}"))
            throw new IllegalArgumentException("C2C 资金意图无效");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO c2c_fund_intents(intent_id,transfer_id,owner_type,owner_id,purpose,
                    source_user_id,target_user_id,asset,amount_units)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_type,owner_id,purpose) DO NOTHING
                """, id, transferId(id), ownerType, ownerId, purpose, sourceUserId, targetUserId, asset, units);
        var intent = jdbc.query("SELECT * FROM c2c_fund_intents WHERE owner_type=? AND owner_id=? AND purpose=?",
                this::read, ownerType, ownerId, purpose).getFirst();
        if (intent.sourceUserId() != sourceUserId || intent.targetUserId() != targetUserId
                || !intent.asset().equals(asset) || intent.amountUnits() != units)
            throw new IllegalStateException("C2C 原资金意图参数冲突，不能重新扣款或改收款人");
        return intent;
    }

    Intent find(String ownerType, UUID ownerId, String purpose) {
        return jdbc.query("SELECT * FROM c2c_fund_intents WHERE owner_type=? AND owner_id=? AND purpose=?",
                this::read,ownerType,ownerId,purpose).stream().findFirst().orElse(null);
    }

    Intent get(UUID id) {
        var rows = jdbc.query("SELECT * FROM c2c_fund_intents WHERE intent_id=?", this::read, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("C2C 资金意图不存在");
        return rows.getFirst();
    }

    List<Intent> due(int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("C2C 续办批量无效");
        return jdbc.query("""
                SELECT * FROM c2c_fund_intents WHERE status NOT IN ('APPLIED','REJECTED') AND retry_after<=now()
                ORDER BY retry_after,created_at LIMIT ?
                """, this::read, limit);
    }

    void advance(Intent intent, String next) {
        jdbc.update("""
                UPDATE c2c_fund_intents SET status=?,error_code=NULL,updated_at=now(),retry_after=now()
                WHERE intent_id=? AND status=?
                """, next, intent.id(), intent.status());
    }

    void reject(Intent intent, String code) {
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "SOURCE_DEBIT_REJECTED";
        jdbc.update("""
                UPDATE c2c_fund_intents SET status='REJECTED',error_code=?,updated_at=now()
                WHERE intent_id=? AND status='PREPARED'
                """, safeCode, intent.id());
    }

    void retry(Intent intent, String code) {
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "RESULT_UNKNOWN";
        jdbc.update("""
                UPDATE c2c_fund_intents SET attempts=attempts+1,error_code=?,updated_at=now(),
                    retry_after=now()+interval '3 seconds' WHERE intent_id=? AND status=?
                """, safeCode, intent.id(), intent.status());
    }

    private Intent read(java.sql.ResultSet row, int n) throws java.sql.SQLException {
        return new Intent(row.getObject("intent_id", UUID.class), row.getLong("transfer_id"), row.getString("owner_type"),
                row.getObject("owner_id", UUID.class), row.getString("purpose"), row.getLong("source_user_id"),
                row.getLong("target_user_id"), row.getString("asset"), row.getLong("amount_units"), row.getString("status"));
    }

    private static long transferId(UUID id) {
        try {
            long value = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest(("c2c-funds:" + id).getBytes(StandardCharsets.UTF_8))).getLong() & Long.MAX_VALUE;
            return value == 0 ? 1 : value;
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
