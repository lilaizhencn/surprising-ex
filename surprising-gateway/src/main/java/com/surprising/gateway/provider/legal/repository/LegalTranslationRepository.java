package com.surprising.gateway.provider.legal.repository;

import com.surprising.gateway.provider.legal.model.LegalModels.*;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Only writes gateway_legal_translations. The service permits replacement only for a locked draft. */
@Repository
public class LegalTranslationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public LegalTranslationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc); }
    public void replace(long id,List<Translation> translations) {
        jdbc.update("DELETE FROM gateway_legal_translations WHERE revision_id=?",id);
        jdbc.batchUpdate("INSERT INTO gateway_legal_translations(revision_id,locale,title,body_html) VALUES (?,?,?,?)",
                translations.stream().map(t->new Object[]{id,t.locale(),t.title(),t.bodyHtml()}).toList());
    }
    public List<TranslationRow> findFor(List<Long> ids) {
        if(ids.isEmpty())return List.of();
        return named.query("SELECT * FROM gateway_legal_translations WHERE revision_id IN (:ids) ORDER BY revision_id,locale",Map.of("ids",ids),
                (rs,n)->new TranslationRow(rs.getLong("revision_id"),new Translation(rs.getString("locale"),rs.getString("title"),rs.getString("body_html"))));
    }
    public boolean hasLocale(long id,String locale) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM gateway_legal_translations WHERE revision_id=? AND locale=?)",Boolean.class,id,locale));
    }
}
