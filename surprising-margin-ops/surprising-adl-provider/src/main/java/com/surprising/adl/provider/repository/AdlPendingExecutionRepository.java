package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlSagaState;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责锁定 {@code adl_execution_sagas} 表中的待自愈记录。
 *
 * <p>账户命令终态由独立的单表 Repository 在同一 Service 事务中读取，避免 Repository 跨表连接；该查询仅用于
 * 在线资金一致性自愈，不提供后台时间线、对账或运营报表。</p>
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
                SELECT execution_id, product_line, account_type, deficit_user_id, target_user_id, asset,
                       symbol, target_side, target_position_side, closed_quantity_steps, entry_price_ticks,
                       mark_price_ticks, requested_deficit_units, realized_profit_units, covered_units,
                       priority_score_ppm, reserve_command_id, target_command_id, finalize_command_id,
                       release_command_id, status, error_code, error_message
                  FROM adl_execution_sagas
                 WHERE product_line = ? AND status IN ('PENDING', 'RELEASING')
                 ORDER BY created_at ASC, execution_id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
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
                null, null, null, null, null, rs.getString("error_code"), rs.getString("error_message")),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
    }
}
