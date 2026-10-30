package com.surprising.gateway.provider.legal.repository;

import com.surprising.gateway.provider.legal.model.LegalModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Only reads/writes gateway_legal_audit; mandatory audit failure rolls back the business transaction. */
@Repository
public class LegalAuditRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public LegalAuditRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public void append(Revision revision,String action,long actor,String reason,Instant now) {
        jdbc.update("INSERT INTO gateway_legal_audit(revision_id,action,admin_user_id,reason,snapshot,created_at) VALUES (?,?,?,?,?::jsonb,?)",
                revision.revisionId(),action,actor,reason,json.writeValueAsString(revision),Timestamp.from(now));
    }
    public List<Audit> list(long id) {
        return jdbc.query("SELECT * FROM gateway_legal_audit WHERE revision_id=? ORDER BY audit_id DESC LIMIT 100",
                (rs,n)->new Audit(rs.getLong("audit_id"),rs.getLong("revision_id"),rs.getString("action"),rs.getLong("admin_user_id"),rs.getString("reason"),rs.getString("snapshot"),rs.getTimestamp("created_at").toInstant()),id);
    }
}
