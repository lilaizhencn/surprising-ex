package com.surprising.trading.matching.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_matching_assets} 表。 */
@Repository
public class MatchingAssetRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingAssetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Integer> findId(String asset) {
        return jdbcTemplate.query("""
                SELECT asset_id FROM trading_matching_assets WHERE asset = ?
                """, (rs, rowNum) -> rs.getInt("asset_id"), asset).stream().findFirst();
    }

    public void insert(String asset, int assetId) {
        jdbcTemplate.update("""
                INSERT INTO trading_matching_assets (asset, asset_id, created_at)
                VALUES (?, ?, now())
                ON CONFLICT (asset) DO NOTHING
                """, asset, assetId);
    }
}
