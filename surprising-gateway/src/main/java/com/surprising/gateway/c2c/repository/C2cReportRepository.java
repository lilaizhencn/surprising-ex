package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAdminRows.Report;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_reports。 */
@Repository
public class C2cReportRepository {
    private final JdbcTemplate jdbc;

    public C2cReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Report> adminPage(String status, Long userId, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_reports WHERE (CAST(? AS text) IS NULL OR status=?)
                    AND (CAST(? AS bigint) IS NULL OR user_id=? OR target_user_id=?)
                ORDER BY created_at DESC,report_id DESC LIMIT ? OFFSET ?
                """, (r, n) -> new Report(r.getObject("report_id", UUID.class), r.getLong("user_id"),
                        r.getLong("target_user_id"), r.getObject("order_id", UUID.class), r.getString("reason"),
                        r.getString("status"), r.getString("review_reason"), r.getTimestamp("created_at").toInstant()),
                status, status, userId, userId, userId, count, offset);
    }
}
