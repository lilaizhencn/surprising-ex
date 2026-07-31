package com.surprising.funding.provider.repository;

import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentResult;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 查找已终态但资金费支付仍待确认的账户命令。
 *
 * <p>不可拆原因：恢复任务必须通过 command_id 在同一快照中关联 funding_payments 与 account_commands，
 * 才能只修复真正遗漏或乱序的结果事件。拆成两次查询会把状态变化窗口误判为待修复记录。该查询仅用于在线一致性
 * 自愈，不用于后台时间线、资金对账或运营报表。</p>
 */
@Repository
public class FundingPendingCommandRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingPendingCommandRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public List<FundingPaymentResult> findTerminal(int limit) {
        return jdbcTemplate.query("""
                SELECT c.command_id, c.user_id, c.status, c.error_code, c.error_message, c.completed_at
                  FROM funding_payments p
                  JOIN account_commands c ON c.command_id = p.command_id
                 WHERE p.status = 'PENDING'
                   AND c.product_line = ?
                   AND c.command_type = 'FUNDING_SETTLE'
                   AND c.status IN ('APPLIED', 'REJECTED')
                 ORDER BY c.completed_at ASC, c.command_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> new FundingPaymentResult(
                rs.getString("command_id"), rs.getLong("user_id"), rs.getString("status"),
                rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("completed_at").toInstant()),
                properties.getKafka().getProductLine().name(), Math.max(1, limit));
    }
}
