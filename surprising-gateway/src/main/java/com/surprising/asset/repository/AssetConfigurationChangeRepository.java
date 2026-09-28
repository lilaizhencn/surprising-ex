package com.surprising.asset.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssetConfigurationChangeRepository {
    private final JdbcTemplate jdbc;
    public AssetConfigurationChangeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(int assetId, Integer networkId, long operatorId, String reason, String before, String after) {
        jdbc.update("""
                INSERT INTO asset_configuration_changes(asset_id,network_id,operator_id,reason,before_values,after_values)
                VALUES (?,?,?,?,?::jsonb,?::jsonb)
                """, assetId, networkId, operatorId, reason, before, after);
    }
}
