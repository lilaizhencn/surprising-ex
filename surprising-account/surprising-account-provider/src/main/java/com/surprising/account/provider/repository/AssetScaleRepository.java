package com.surprising.account.provider.repository;

import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账户资产精度单表仓储。 */
@Repository
public class AssetScaleRepository {

    private final JdbcTemplate jdbcTemplate;

    public AssetScaleRepository(JdbcTemplate jdbcTemplate) {
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
