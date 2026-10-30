package com.surprising.gateway.provider.legal.repository;

import com.surprising.gateway.provider.legal.model.LegalModels.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only reads/writes gateway_legal_consents. Repeat confirmation preserves the original timestamp. */
@Repository
public class LegalConsentRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public LegalConsentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc); }
    public List<Consent> findFor(long userId,List<Long> ids) {
        if(ids.isEmpty())return List.of();
        return named.query("SELECT revision_id,locale,acknowledged_at FROM gateway_legal_consents WHERE user_id=:userId AND revision_id IN (:ids)",Map.of("userId",userId,"ids",ids),
                (rs,n)->new Consent(rs.getLong("revision_id"),rs.getString("locale"),rs.getTimestamp("acknowledged_at").toInstant()));
    }
    public void insert(long userId,List<Acceptance> documents,Instant now) {
        jdbc.batchUpdate("INSERT INTO gateway_legal_consents(user_id,revision_id,locale,acknowledged_at) VALUES (?,?,?,?) ON CONFLICT(user_id,revision_id) DO NOTHING",
                documents.stream().map(d->new Object[]{userId,d.revisionId(),d.locale(),Timestamp.from(now)}).toList());
    }
}
