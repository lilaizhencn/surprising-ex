package com.surprising.gateway.provider.legal.repository;

import com.surprising.gateway.provider.legal.model.LegalDocumentType;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only writes gateway_legal_revisions. Publication is serialized per document type. */
@Repository
public class LegalRevisionRepository {
    private final JdbcTemplate jdbc;
    public LegalRevisionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void lockType(LegalDocumentType type) {
        jdbc.execute((java.sql.Connection connection)->{
            try(var statement=connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1,"surprising/legal/"+type.name()); statement.execute(); return null;
            }
        });
    }
    public List<Long> currentAccountRevisionIds() {
        return jdbc.queryForList("""
                SELECT r.revision_id FROM gateway_legal_revisions r
                WHERE r.status='PUBLISHED' AND r.document_type IN ('USER_AGREEMENT','PRIVACY_POLICY')
                  AND EXISTS(SELECT 1 FROM gateway_legal_translations t WHERE t.revision_id=r.revision_id AND t.locale='en-US')
                ORDER BY r.document_type
                """, Long.class);
    }
    public long create(Content content,long actor,Instant now) {
        return jdbc.queryForObject("INSERT INTO gateway_legal_revisions(document_type,revision_label,created_by,updated_by,created_at,updated_at) VALUES (?,?,?,?,?,?) RETURNING revision_id",
                Long.class,content.type().name(),content.revisionLabel(),actor,actor,Timestamp.from(now),Timestamp.from(now));
    }
    public boolean update(long id,long version,Content content,long actor,Instant now) {
        return jdbc.update("UPDATE gateway_legal_revisions SET revision_label=?,version=version+1,updated_by=?,updated_at=? WHERE revision_id=? AND version=? AND status='DRAFT' AND document_type=?",
                content.revisionLabel(),actor,Timestamp.from(now),id,version,content.type().name())==1;
    }
    public void supersedeCurrent(LegalDocumentType type,long actor,Instant now) {
        jdbc.update("UPDATE gateway_legal_revisions SET status='SUPERSEDED',version=version+1,updated_by=?,updated_at=? WHERE document_type=? AND status='PUBLISHED'",
                actor,Timestamp.from(now),type.name());
    }
    public boolean publish(long id,long version,long actor,Instant now) {
        return jdbc.update("UPDATE gateway_legal_revisions SET status='PUBLISHED',version=version+1,published_at=?,updated_at=?,updated_by=? WHERE revision_id=? AND version=? AND status='DRAFT'",
                Timestamp.from(now),Timestamp.from(now),actor,id,version)==1;
    }
    public boolean withdraw(long id,long version,long actor,Instant now) {
        return jdbc.update("UPDATE gateway_legal_revisions SET status='WITHDRAWN',version=version+1,updated_at=?,updated_by=? WHERE revision_id=? AND version=? AND status IN ('DRAFT','PUBLISHED')",
                Timestamp.from(now),actor,id,version)==1;
    }
    public Optional<RevisionRow> find(long id) {
        return jdbc.query("SELECT * FROM gateway_legal_revisions WHERE revision_id=?",this::row,id).stream().findFirst();
    }
    public List<RevisionRow> current() {
        return jdbc.query("SELECT * FROM gateway_legal_revisions WHERE status='PUBLISHED' ORDER BY document_type",this::row);
    }
    public List<RevisionRow> list(int offset,int limit) {
        return jdbc.query("SELECT * FROM gateway_legal_revisions ORDER BY updated_at DESC,revision_id DESC LIMIT ? OFFSET ?",this::row,limit,offset);
    }
    private RevisionRow row(ResultSet rs,int ignored) throws SQLException {
        var published=rs.getTimestamp("published_at");
        return new RevisionRow(rs.getLong("revision_id"),LegalDocumentType.valueOf(rs.getString("document_type")),rs.getString("revision_label"),rs.getString("status"),rs.getLong("version"),
                rs.getLong("created_by"),rs.getLong("updated_by"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),published==null?null:published.toInstant());
    }
}
