package com.surprising.account.provider.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Cancels passive close triggers in the same database transaction that closes their position. */
@Service
public class ClosedPositionTriggerOrderPruner {

    public static final String REASON = "POSITION_CLOSED";

    private final JdbcTemplate jdbcTemplate;

    public ClosedPositionTriggerOrderPruner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int prune(ProductLine productLine,
                     long userId,
                     String symbol,
                     MarginMode marginMode,
                     PositionSide positionSide,
                     Instant closedAt) {
        return jdbcTemplate.update("""
                UPDATE trading_trigger_orders
                   SET status = 'CANCELED',
                       reject_reason = ?,
                       updated_at = ?
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND status = 'PENDING'
                """, REASON, Timestamp.from(closedAt), productLine.name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(), PositionSide.defaultIfNull(positionSide).name());
    }
}
