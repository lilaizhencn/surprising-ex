package com.surprising.candlestick.provider.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code account_asset_scales} 表。
 */
@Repository
public class CandlestickAssetScaleRepository {

    private final JdbcTemplate jdbcTemplate;

    public CandlestickAssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> findScaleUnits(String asset) {
        return jdbcTemplate.query("""
                SELECT scale_units
                  FROM account_asset_scales
                 WHERE asset = ?
                """, (rs, rowNum) -> rs.getLong("scale_units"), asset).stream().findFirst();
    }
}
