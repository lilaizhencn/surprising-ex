package com.surprising.insurance.provider.repository;

import com.surprising.insurance.provider.model.InsurancePendingCoverage;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 锁定待自愈的保险覆盖记录及其账户命令终态。
 *
 * <p>不可拆原因：必须在锁定 insurance_deficit_coverages 行的同一数据库快照中读取 reserve 与 finalize
 * 两条 account_commands 终态，才能保证基金预留只释放或扣减一次。拆成多个查询会在命令状态变化窗口造成
 * 重复扣款或重复释放。该查询只服务在线资金一致性自愈，不提供后台时间线、对账或运营报表。</p>
 */
@Repository
public class InsurancePendingCoverageRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsurancePendingCoverageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InsurancePendingCoverage> lock(String accountType, int limit) {
        return jdbcTemplate.query("""
                SELECT c.coverage_id, c.account_type, c.user_id, c.asset, c.covered_units,
                       c.reserve_command_id, c.finalize_command_id, c.status,
                       reserve.status AS reserve_status,
                       finalize.status AS finalize_status,
                       finalize.result_payload::text AS finalize_result,
                       COALESCE(reserve.error_code, finalize.error_code) AS error_code,
                       COALESCE(reserve.error_message, finalize.error_message) AS error_message
                  FROM insurance_deficit_coverages c
                  LEFT JOIN account_commands reserve ON reserve.command_id = c.reserve_command_id
                  LEFT JOIN account_commands finalize ON finalize.command_id = c.finalize_command_id
                 WHERE c.account_type = ?
                   AND c.status IN ('PENDING_RESERVE', 'PENDING_FINALIZE')
                 ORDER BY c.created_at ASC, c.coverage_id ASC
                 LIMIT ?
                 FOR UPDATE OF c SKIP LOCKED
                """, (rs, rowNum) -> new InsurancePendingCoverage(
                rs.getLong("coverage_id"), rs.getString("account_type"), rs.getLong("user_id"),
                rs.getString("asset"), rs.getLong("covered_units"), rs.getString("reserve_command_id"),
                rs.getString("finalize_command_id"), rs.getString("status"),
                rs.getString("reserve_status"), rs.getString("finalize_status"),
                rs.getString("finalize_result"), rs.getString("error_code"), rs.getString("error_message")),
                accountType, Math.max(1, limit));
    }
}
