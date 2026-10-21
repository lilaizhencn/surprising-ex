package com.surprising.gateway.provider.announcement.repository;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import tools.jackson.databind.ObjectMapper;

/** 只访问gateway_announcement_audit；快照由Service聚合，本类负责JSON持久化。 */
@Repository
public class AnnouncementAuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AnnouncementAuditRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public List<AuditEntry> list(long id) {
        return jdbc.query("""
                SELECT audit_id, announcement_id, action, admin_user_id, admin_username,
                       reason, snapshot::text AS snapshot, created_at
                  FROM gateway_announcement_audit
                 WHERE announcement_id = ?
                 ORDER BY audit_id DESC
                 LIMIT 200
                """, (rs, rowNum) -> new AuditEntry(rs.getLong("audit_id"), rs.getLong("announcement_id"),
                rs.getString("action"), rs.getLong("admin_user_id"), rs.getString("admin_username"),
                rs.getString("reason"), rs.getString("snapshot"), instant(rs, "created_at")), id);
    }
    public void append(Announcement snapshot,String action,long actorId,String username,String reason,Instant now) {
        jdbc.update("""
                INSERT INTO gateway_announcement_audit(announcement_id,action,admin_user_id,admin_username,reason,snapshot,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """,snapshot.announcementId(),action,actorId,username,reason==null||reason.isBlank()?null:reason,
                json.writeValueAsString(snapshot),Timestamp.from(now));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
