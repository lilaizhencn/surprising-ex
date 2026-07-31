package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlSagaState;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 锁定待自愈的 ADL saga 及其账户命令终态。
 *
 * <p>不可拆原因：必须在锁定 adl_execution_sagas 行的同一数据库快照中读取 reserve、target、finalize
 * 与 release 的 account_commands 终态，才能唯一决定完成、失败或补偿分支。拆分会在命令状态变化窗口导致
 * 重复减仓或重复释放。该查询仅用于在线资金一致性自愈，不提供后台时间线、对账或运营报表。</p>
 */
@Repository
public class AdlPendingExecutionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AdlProperties properties;

    public AdlPendingExecutionRepository(JdbcTemplate jdbcTemplate, AdlProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<AdlSagaState> lock(int limit) {
        return jdbcTemplate.query("""
                SELECT s.*,
                       reserve.status AS reserve_status,
                       target.status AS target_status,
                       finalize.status AS finalize_status,
                       release.status AS release_status,
                       finalize.result_payload::text AS finalize_result,
                       COALESCE(target.error_code, reserve.error_code, finalize.error_code, release.error_code)
                           AS terminal_error_code,
                       COALESCE(target.error_message, reserve.error_message, finalize.error_message,
                                release.error_message) AS terminal_error_message
                  FROM adl_execution_sagas s
                  LEFT JOIN account_commands reserve ON reserve.command_id = s.reserve_command_id
                  LEFT JOIN account_commands target ON target.command_id = s.target_command_id
                  LEFT JOIN account_commands finalize ON finalize.command_id = s.finalize_command_id
                  LEFT JOIN account_commands release ON release.command_id = s.release_command_id
                 WHERE s.product_line = ? AND s.status IN ('PENDING', 'RELEASING')
                 ORDER BY s.created_at ASC, s.execution_id ASC
                 LIMIT ?
                 FOR UPDATE OF s SKIP LOCKED
                """, (rs, rowNum) -> new AdlSagaState(
                rs.getLong("execution_id"), rs.getString("product_line"), rs.getString("account_type"),
                rs.getLong("deficit_user_id"), rs.getLong("target_user_id"), rs.getString("asset"),
                rs.getString("symbol"), rs.getString("target_side"), rs.getString("target_position_side"),
                rs.getLong("closed_quantity_steps"), rs.getLong("entry_price_ticks"),
                rs.getLong("mark_price_ticks"), rs.getLong("requested_deficit_units"),
                rs.getLong("realized_profit_units"), rs.getLong("covered_units"),
                rs.getLong("priority_score_ppm"), rs.getString("reserve_command_id"),
                rs.getString("target_command_id"), rs.getString("finalize_command_id"),
                rs.getString("release_command_id"), rs.getString("status"),
                rs.getString("reserve_status"), rs.getString("target_status"),
                rs.getString("finalize_status"), rs.getString("release_status"),
                rs.getString("finalize_result"), rs.getString("terminal_error_code"),
                rs.getString("terminal_error_message")),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
    }
}
