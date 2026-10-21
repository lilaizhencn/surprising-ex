package com.surprising.price.settings.repository;

import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.model.PriceSettingsSnapshot;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** 只访问 price_business_settings；JSON 编解码限于此持久化边界。 */
@Repository
public class PriceBusinessSettingsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final tools.jackson.databind.ObjectReader settingsReader;
    public PriceBusinessSettingsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json;
        // 持久化快照也必须完整且类型正确，禁止把字符串、缺失或小数强制转换为业务规则。
        this.settingsReader = json.rebuild()
                .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                        tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                        tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build().readerFor(PriceBusinessSettings.class);
    }

    public void initialize(ProductLine line, PriceBusinessSettings initial) {
        jdbc.update("""
                INSERT INTO price_business_settings(product_line,settings,version,updated_by,reason)
                VALUES (?,?::jsonb,1,'SYSTEM','initial price settings') ON CONFLICT DO NOTHING
                """, line.name(), json.writeValueAsString(initial));
    }
    public PriceSettingsSnapshot find(ProductLine line) {
        return jdbc.queryForObject("SELECT version,settings,updated_by,reason,updated_at FROM price_business_settings WHERE product_line=?",
                (rs, row) -> decode(rs), line.name());
    }
    public Optional<PriceSettingsSnapshot> save(ProductLine line, PriceBusinessSettings settings, long expectedVersion, String admin, String reason) {
        return jdbc.query("""
                UPDATE price_business_settings SET settings=?::jsonb,version=version+1,updated_by=?,reason=?,updated_at=now()
                WHERE product_line=? AND version=? RETURNING version,settings,updated_by,reason,updated_at
                """, (rs, row) -> decode(rs), json.writeValueAsString(settings), admin, reason, line.name(), expectedVersion)
                .stream().findFirst();
    }
    private PriceSettingsSnapshot decode(ResultSet rs) throws SQLException {
        return new PriceSettingsSnapshot(rs.getLong("version"), settingsReader.readValue(rs.getString("settings")),
                rs.getString("updated_by"), rs.getString("reason"), rs.getTimestamp("updated_at").toInstant());
    }
}
