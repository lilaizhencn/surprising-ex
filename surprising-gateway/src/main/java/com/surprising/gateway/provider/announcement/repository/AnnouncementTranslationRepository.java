package com.surprising.gateway.provider.announcement.repository;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 只访问gateway_announcement_translations。 */
@Repository
public class AnnouncementTranslationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public AnnouncementTranslationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc); }
    public List<TranslationRow> findFor(List<Long> ids) {
        if(ids.isEmpty())return List.of();
        return named.query("SELECT announcement_id,locale,title,summary,body FROM gateway_announcement_translations WHERE announcement_id IN (:ids) ORDER BY announcement_id,locale",
                Map.of("ids",ids),(rs,n)->new TranslationRow(rs.getLong("announcement_id"),new Translation(rs.getString("locale"),rs.getString("title"),rs.getString("summary"),rs.getString("body"))));
    }
    public void replace(long id,List<Translation> translations) {
        jdbc.update("DELETE FROM gateway_announcement_translations WHERE announcement_id=?",id);
        jdbc.batchUpdate("INSERT INTO gateway_announcement_translations(announcement_id,locale,title,summary,body) VALUES (?,?,?,?,?)",
                translations.stream().map(t->new Object[]{id,t.locale(),t.title(),t.summary(),t.body()}).toList());
    }
}
