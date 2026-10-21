package com.surprising.derivatives.lifecycle.repository;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.model.LifecycleSettingsSnapshot;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;

/** 只访问 lifecycle_business_settings，始终按产品线读取或比较版本更新。 */
@Repository
public class LifecycleBusinessSettingsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectReader rules;
    public LifecycleBusinessSettingsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        rules = json.rebuild().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build().readerFor(LifecycleBusinessSettings.class);
    }
    public void initialize(ProductLine line, LifecycleBusinessSettings initial) {
        jdbc.update("""
                INSERT INTO lifecycle_business_settings(product_line,settings,version,updated_by,reason)
                VALUES (?,?::jsonb,1,'SYSTEM','initial lifecycle business settings') ON CONFLICT DO NOTHING
                """, line.name(), json.writeValueAsString(initial));
    }
    public LifecycleSettingsSnapshot find(ProductLine line) {
        return jdbc.queryForObject("SELECT version,settings,updated_by,reason,updated_at FROM lifecycle_business_settings WHERE product_line=?",
                (rs, row) -> decode(rs), line.name());
    }
    public Optional<LifecycleSettingsSnapshot> save(ProductLine line, LifecycleBusinessSettings settings,
                                                   long expectedVersion, String admin, String reason) {
        return jdbc.query("""
                UPDATE lifecycle_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING version,settings,updated_by,reason,updated_at
                """, (rs, row) -> decode(rs), json.writeValueAsString(settings), admin, reason, line.name(), expectedVersion)
                .stream().findFirst();
    }
    private LifecycleSettingsSnapshot decode(ResultSet rs) throws SQLException {
        return new LifecycleSettingsSnapshot(rs.getLong("version"), rules.readValue(rs.getString("settings")),
                rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant());
    }
}
