package com.surprising.insurance.provider.repository;

import com.surprising.insurance.provider.model.CoreLiquidationProjection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreInsuranceProjectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public CoreInsuranceProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CoreLiquidationProjection> pendingInsurance(String productLine, int limit) {
        return jdbcTemplate.query("""
                SELECT liquidation_id, user_id, asset, deficit_units
                  FROM core_liquidation_projection
                 WHERE product_line = ? AND status = 'INSURANCE_REQUIRED' AND deficit_units > 0
                 ORDER BY liquidation_id
                 LIMIT ?
                """, (rs, rowNum) -> new CoreLiquidationProjection(rs.getLong("liquidation_id"),
                rs.getLong("user_id"), rs.getString("asset"), rs.getLong("deficit_units")),
                productLine, Math.max(1, limit));
    }
}
