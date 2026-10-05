package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MarketMakerBusinessSettingsStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public MarketMakerBusinessSettingsStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public Settings load(ProductLine line) {
        return jdbc.query("SELECT settings, version, updated_by, reason, updated_at FROM market_maker_business_settings WHERE product_line=?",
                (rs, row) -> new Settings(json.readValue(rs.getString(1), MarketMakerBusinessSettings.class),
                        rs.getLong(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), line.name())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("maker business settings are not initialized"));
    }

    public void initializeDisabled(ProductLine line) {
        jdbc.update("""
                INSERT INTO market_maker_business_settings(product_line, settings, version, updated_by, reason)
                VALUES (?, ?::jsonb, 1, 'SYSTEM:INITIALIZATION', 'Initialize disabled maker settings')
                ON CONFLICT (product_line) DO NOTHING
                """, line.name(), json.writeValueAsString(MarketMakerBusinessSettings.initialDisabled()));
    }

    @org.springframework.transaction.annotation.Transactional
    public Settings save(ProductLine line, MarketMakerBusinessSettings settings, long expectedVersion, String admin, String reason) {
        if (jdbc.update("""
                UPDATE market_maker_business_settings SET settings=?::jsonb, version=version+1,
                    updated_by=?, reason=?, updated_at=now() WHERE product_line=? AND version=?
                """, json.writeValueAsString(settings), admin, reason, line.name(), expectedVersion) != 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "做市公共设置已更新，请重新加载");
        return load(line);
    }

    public record Settings(MarketMakerBusinessSettings settings, long version, String updatedBy, String reason, Instant updatedAt) {}
}
