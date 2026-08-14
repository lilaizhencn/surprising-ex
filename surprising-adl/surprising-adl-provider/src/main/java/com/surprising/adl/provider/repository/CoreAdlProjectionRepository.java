package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.model.CoreAdlLiquidationProjection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreAdlProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoreAdlProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CoreAdlLiquidationProjection> pending(String productLine, int limit) {
        return jdbcTemplate.query("""
                SELECT liquidation_id, user_id, symbol, asset, signed_quantity_steps, deficit_units
                  FROM core_liquidation_projection
                 WHERE product_line = ? AND status = 'ADL_REQUIRED' AND deficit_units > 0
                 ORDER BY liquidation_id
                 LIMIT ?
                """, (rs, rowNum) -> new CoreAdlLiquidationProjection(rs.getLong("liquidation_id"),
                rs.getLong("user_id"), rs.getString("symbol"), rs.getString("asset"),
                rs.getLong("signed_quantity_steps"), rs.getLong("deficit_units")),
                productLine, Math.max(1, limit));
    }
}
