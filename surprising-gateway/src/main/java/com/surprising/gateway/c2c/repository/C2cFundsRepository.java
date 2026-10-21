package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cFundTotals;
import com.surprising.gateway.c2c.model.C2cProfileRows.Escrow;
import com.surprising.gateway.c2c.model.C2cAdminRows.FundIntent;
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

    public List<FundIntent> adminPage(String status, UUID ownerId, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_fund_intents WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS uuid) IS NULL OR owner_id=?) ORDER BY updated_at DESC,intent_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> new FundIntent(r.getObject("intent_id", UUID.class), r.getLong("transfer_id"),
                        r.getString("owner_type"), r.getObject("owner_id", UUID.class), r.getString("purpose"),
                        r.getLong("source_user_id"), r.getLong("target_user_id"), r.getString("asset"), r.getLong("amount_units"),
                        r.getString("status"), r.getString("error_code"), r.getInt("attempts"),
                        r.getTimestamp("retry_after").toInstant(), r.getTimestamp("updated_at").toInstant()),
                status, status, ownerId, ownerId, count, offset);
    }

    public Intent createOrGet(String ownerType, UUID ownerId, String purpose,
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

    public Intent find(String ownerType, UUID ownerId, String purpose) {
        return jdbc.query("SELECT * FROM c2c_fund_intents WHERE owner_type=? AND owner_id=? AND purpose=?",
                this::read,ownerType,ownerId,purpose).stream().findFirst().orElse(null);
    }

    public Intent get(UUID id) {
        var rows = jdbc.query("SELECT * FROM c2c_fund_intents WHERE intent_id=?", this::read, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("C2C 资金意图不存在");
        return rows.getFirst();
    }

    public List<Intent> due(int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("C2C 续办批量无效");
        return jdbc.query("""
                SELECT * FROM c2c_fund_intents WHERE status NOT IN ('APPLIED','REJECTED') AND retry_after<=now()
                ORDER BY retry_after,created_at LIMIT ?
                """, this::read, limit);
    }

    public void advance(Intent intent, String next) {
        jdbc.update("""
                UPDATE c2c_fund_intents SET status=?,error_code=NULL,updated_at=now(),retry_after=now()
                WHERE intent_id=? AND status=?
                """, next, intent.id(), intent.status());
    }

    public void reject(Intent intent, String code) {
        String safeCode = code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "SOURCE_DEBIT_REJECTED";
        jdbc.update("""
                UPDATE c2c_fund_intents SET status='REJECTED',error_code=?,updated_at=now()
                WHERE intent_id=? AND status='PREPARED'
                """, safeCode, intent.id());
    }

    public void retry(Intent intent, String code) {
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
    /**
     * 资金意图是托管统计根；按owner_type关联唯一广告/订单/质押仅确定原资产所有者，资产只提供权威精度。
     * 只读已持久意图并按币种聚合，不创建SQL余额、不调用Core；未确认或结算拒绝由Service明确展示未知。
     */
    public List<Escrow> escrowByOwner(long userId) {
        return jdbc.query("""
                SELECT f.asset,a.scale_units,
                    COALESCE(sum(CASE WHEN f.status='APPLIED' AND f.purpose IN ('AD_RESERVE','ORDER_RESERVE','MERCHANT_BOND') THEN f.amount_units::numeric
                        WHEN f.status='APPLIED' THEN -f.amount_units::numeric ELSE 0 END),0) AS held,
                    count(*) FILTER(WHERE f.status NOT IN ('APPLIED','REJECTED')) AS pending,
                    count(*) FILTER(WHERE f.status='REJECTED' AND f.purpose NOT IN ('AD_RESERVE','ORDER_RESERVE','MERCHANT_BOND')) AS failed
                FROM c2c_fund_intents f LEFT JOIN c2c_ads ad ON f.owner_type='AD' AND f.owner_id=ad.ad_id
                LEFT JOIN c2c_orders o ON f.owner_type='ORDER' AND f.owner_id=o.order_id
                LEFT JOIN c2c_merchant_bonds b ON f.owner_type='MERCHANT' AND f.owner_id=b.bond_id JOIN assets a ON a.asset=f.asset
                WHERE COALESCE(ad.maker_user_id,o.seller_user_id,b.user_id)=? GROUP BY f.asset,a.scale_units
                """,(r,n)->new Escrow(r.getString("asset"),r.getLong("scale_units"),r.getBigDecimal("held"),r.getLong("pending"),r.getLong("failed")),userId);
    }

    public boolean hasPendingForUser(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_fund_intents WHERE (source_user_id=? OR target_user_id=?) AND status NOT IN ('APPLIED','REJECTED'))
                """,Boolean.class,userId,userId));
    }
    public long pendingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE status NOT IN ('APPLIED','REJECTED')",Long.class);
    }
    public long failedSettlementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents WHERE status='REJECTED' AND purpose NOT IN ('AD_RESERVE','ORDER_RESERVE','MERCHANT_BOND')",Long.class);
    }
    public List<C2cFundTotals> confirmedTotals(long escrow,long fees) {
        return jdbc.query("""
                SELECT asset,COALESCE(sum(CASE WHEN target_user_id=? THEN amount_units::numeric WHEN source_user_id=? THEN -amount_units::numeric ELSE 0 END),0) AS escrow,
                    COALESCE(sum(CASE WHEN target_user_id=? THEN amount_units::numeric WHEN source_user_id=? THEN -amount_units::numeric ELSE 0 END),0) AS fees
                FROM c2c_fund_intents WHERE status='APPLIED' GROUP BY asset
                """,(r,n)->new C2cFundTotals(r.getString("asset"),r.getBigDecimal("escrow"),r.getBigDecimal("fees")),escrow,escrow,fees,fees);
    }

}
