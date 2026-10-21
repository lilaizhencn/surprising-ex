package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.Appeal;
import com.surprising.gateway.c2c.model.C2cAppealTimeline;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_appeals。 */
@Repository
public class C2cAppealRepository {
    private final JdbcTemplate jdbc;

    public C2cAppealRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<C2cAppealTimeline> timeline(UUID orderId) {
        return jdbc.query("""
                SELECT appeal_id,opener_user_id,reason,status,decision,decision_reason,created_at,resolved_at
                FROM c2c_appeals WHERE order_id=? ORDER BY created_at
                """, (r, n) -> new C2cAppealTimeline(r.getObject("appeal_id", UUID.class), r.getLong("opener_user_id"),
                        r.getString("reason"), r.getString("status"), r.getString("decision"), r.getString("decision_reason"),
                        r.getTimestamp("created_at").toInstant(),
                        r.getTimestamp("resolved_at") == null ? null : r.getTimestamp("resolved_at").toInstant()), orderId);
    }

    public Optional<UUID> openForOrder(UUID orderId) {
        return jdbc.query("SELECT appeal_id FROM c2c_appeals WHERE order_id=? AND status IN ('OPEN','REVIEWING')",
                (r, n) -> r.getObject(1, UUID.class), orderId).stream().findFirst();
    }

    public void insert(UUID id, UUID orderId, long opener, String previousStatus, String reason) {
        jdbc.update("INSERT INTO c2c_appeals(appeal_id,order_id,opener_user_id,previous_order_status,reason) VALUES(?,?,?,?,?)",
                id, orderId, opener, previousStatus, reason);
    }

    public Optional<String> statusForUpdate(UUID id) {
        return jdbc.query("SELECT status FROM c2c_appeals WHERE appeal_id=? FOR UPDATE", (r, n) -> r.getString(1), id)
                .stream().findFirst();
    }

    public void resolve(UUID id, String decision, String reason, long administrator, long approvalId) {
        jdbc.update("""
                UPDATE c2c_appeals SET status='RESOLVED',decision=?,decision_reason=?,decided_by=?,approval_id=?,resolved_at=now()
                WHERE appeal_id=?
                """, decision, reason, administrator, approvalId, id);
    }

    public Optional<UUID> orderId(UUID id) {
        return jdbc.query("SELECT order_id FROM c2c_appeals WHERE appeal_id=?",
                (r, n) -> r.getObject(1, UUID.class), id).stream().findFirst();
    }

    public List<Appeal> adminPage(String status, UUID orderId, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_appeals WHERE (CAST(? AS text) IS NULL OR status=?) AND (CAST(? AS uuid) IS NULL OR order_id=?)
                ORDER BY created_at DESC,appeal_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> new Appeal(r.getObject("appeal_id", UUID.class), r.getObject("order_id", UUID.class),
                        r.getLong("opener_user_id"), r.getString("status"), r.getString("reason"), r.getString("decision"),
                        r.getString("decision_reason"), r.getObject("approval_id", Long.class), r.getObject("decided_by", Long.class),
                        r.getTimestamp("created_at").toInstant()), status, status, orderId, orderId, count, offset);
    }
}
