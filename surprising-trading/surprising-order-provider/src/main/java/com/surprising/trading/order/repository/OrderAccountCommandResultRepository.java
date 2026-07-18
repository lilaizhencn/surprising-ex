package com.surprising.trading.order.repository;

import com.surprising.account.api.model.AccountCommandResultEvent;
import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderAccountCommandResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderAccountCommandResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AccountCommandResultEvent> terminalPendingOrderReservations(ProductLine productLine, int limit) {
        return jdbcTemplate.query("""
                SELECT c.command_id, c.product_line, c.user_id, c.command_type, c.status,
                       c.source, c.source_reference, c.result_payload::text AS result_payload,
                       c.error_code, c.error_message, c.completed_at, c.trace_id
                  FROM account_commands c
                  JOIN trading_orders o
                    ON o.order_id = CAST(c.source_reference AS bigint)
                   AND o.product_line = c.product_line
                   AND o.user_id = c.user_id
                 WHERE c.product_line = ?
                   AND c.source = 'ORDER'
                   AND c.command_type = 'ORDER_RESERVE'
                   AND c.status IN ('APPLIED', 'REJECTED')
                   AND o.status = 'PENDING_RESERVE'
                 ORDER BY c.completed_at ASC, c.command_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> new AccountCommandResultEvent(
                rowNum + 1L,
                rs.getString("command_id"),
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getLong("user_id"),
                AccountUserCommandType.valueOf(rs.getString("command_type")),
                AccountCommandStatus.valueOf(rs.getString("status")),
                rs.getString("source"),
                rs.getString("source_reference"),
                rs.getString("result_payload"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getTimestamp("completed_at").toInstant(),
                rs.getString("trace_id")),
                productLine.name(), Math.max(1, limit));
    }
}
