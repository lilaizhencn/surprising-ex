package com.surprising.instrument.provider.repository;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取 {@code account_asset_scales} 表，为 Instrument 启动快照提供资产精度。
 */
@Repository
public class InstrumentAssetScaleRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentAssetScaleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> findAll() {
        return jdbcTemplate.query("""
                SELECT asset, scale_units
                  FROM account_asset_scales
                """, (rs, rowNum) -> Map.entry(rs.getString("asset"), rs.getLong("scale_units")))
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
