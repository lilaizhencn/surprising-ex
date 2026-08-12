package com.surprising.insurance.provider.repository;

import com.surprising.insurance.provider.model.InsuranceDeficitRow;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 account_product_deficits 表。
 */
@Repository
public class InsuranceProductDeficitRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceProductDeficitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InsuranceDeficitRow> findPositive(String accountType, int limit) {
        return jdbcTemplate.query("""
                SELECT account_type, user_id, asset,
                       deficit_units - reserved_units AS deficit_units
                  FROM account_product_deficits
                 WHERE account_type = ?
                   AND deficit_units - reserved_units > 0
                 ORDER BY updated_at ASC
                 LIMIT ?
                """, (rs, rowNum) -> new InsuranceDeficitRow(
                rs.getString("account_type"),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("deficit_units")), accountType, limit);
    }
}
