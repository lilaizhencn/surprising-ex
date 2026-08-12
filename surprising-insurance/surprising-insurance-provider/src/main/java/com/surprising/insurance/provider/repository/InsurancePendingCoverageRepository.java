package com.surprising.insurance.provider.repository;

import com.surprising.insurance.provider.model.InsurancePendingCoverage;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责锁定 {@code insurance_deficit_coverages} 表中的待自愈记录。
 *
 * <p>账户命令终态由独立的单表 Repository 在同一 Service 事务中锁定读取，避免 Repository 跨表连接。</p>
 */
@Repository
public class InsurancePendingCoverageRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsurancePendingCoverageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InsurancePendingCoverage> lock(String accountType, int limit) {
        return jdbcTemplate.query("""
                SELECT coverage_id, account_type, user_id, asset, covered_units,
                       reserve_command_id, finalize_command_id, status,
                       error_code, error_message
                  FROM insurance_deficit_coverages
                 WHERE account_type = ?
                   AND status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                 ORDER BY created_at ASC, coverage_id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new InsurancePendingCoverage(
                rs.getLong("coverage_id"), rs.getString("account_type"), rs.getLong("user_id"),
                rs.getString("asset"), rs.getLong("covered_units"), rs.getString("reserve_command_id"),
                rs.getString("finalize_command_id"), rs.getString("status"),
                null, null, null, rs.getString("error_code"), rs.getString("error_message")),
                accountType, Math.max(1, limit));
    }
}
