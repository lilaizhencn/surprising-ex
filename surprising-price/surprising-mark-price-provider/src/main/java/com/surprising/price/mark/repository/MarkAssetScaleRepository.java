package com.surprising.price.mark.repository;

import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code account_asset_scales} 表。 */
@Repository
public class MarkAssetScaleRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarkAssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptionalLong findScaleUnits(String asset) {
        return jdbcTemplate.query("""
                SELECT scale_units
                  FROM account_asset_scales
                 WHERE asset = ?
                """, (rs, rowNum) -> rs.getLong("scale_units"), asset)
                .stream()
                .mapToLong(Long::longValue)
                .findFirst();
    }
}
