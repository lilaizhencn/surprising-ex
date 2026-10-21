package com.surprising.gateway.provider.announcement.repository;

import com.surprising.gateway.provider.announcement.model.AnnouncementModels.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 只访问gateway_announcement_targets。 */
@Repository
public class AnnouncementTargetRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public AnnouncementTargetRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc); }
    public List<TargetRow> findFor(List<Long> ids) {
        if(ids.isEmpty())return List.of();
        return named.query("SELECT announcement_id,target_type,target_value FROM gateway_announcement_targets WHERE announcement_id IN (:ids) ORDER BY announcement_id,target_type,target_value",
                Map.of("ids",ids),(rs,n)->new TargetRow(rs.getLong("announcement_id"),rs.getString("target_type"),rs.getString("target_value")));
    }
    public void replace(long id,Content content) {
        jdbc.update("DELETE FROM gateway_announcement_targets WHERE announcement_id=?",id);
        var rows=new ArrayList<Object[]>();
        content.productLines().forEach(v->rows.add(new Object[]{id,"PRODUCT_LINE",v}));
        content.platforms().forEach(v->rows.add(new Object[]{id,"PLATFORM",v}));
        content.placements().forEach(v->rows.add(new Object[]{id,"PLACEMENT",v}));
        jdbc.batchUpdate("INSERT INTO gateway_announcement_targets(announcement_id,target_type,target_value) VALUES (?,?,?)",rows);
    }
}
