package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.Appeal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_appeals。 */
@Repository
public class C2cAppealRepository {
    private final JdbcTemplate jdbc;

    public C2cAppealRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
