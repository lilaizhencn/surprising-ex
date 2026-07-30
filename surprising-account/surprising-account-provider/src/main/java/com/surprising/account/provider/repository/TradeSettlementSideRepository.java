package com.surprising.account.provider.repository;

import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchTradeEvent;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TradeSettlementSideRepository {

    private final JdbcTemplate jdbcTemplate;

    public TradeSettlementSideRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void complete(ProductLine productLine,
                         MatchTradeEvent trade,
                         TradeParticipantRole role,
                         String commandId,
                         long orderMarginConsumedUnits,
                         long orderMarginReleasedUnits,
                         Instant now) {
        long orderId = role == TradeParticipantRole.TAKER ? trade.takerOrderId() : trade.makerOrderId();
        int rows = jdbcTemplate.update("""
                INSERT INTO account_trade_settlement_sides (
                    product_line, symbol, trade_id, participant_role,
                    taker_user_id, maker_user_id, command_id, order_id,
                    order_margin_consumed_units, order_margin_released_units, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (product_line, symbol, trade_id, participant_role) DO NOTHING
                """,
                productLine.name(), trade.symbol(), trade.tradeId(), role.name(), trade.takerUserId(),
                trade.makerUserId(), commandId, orderId, orderMarginConsumedUnits,
                orderMarginReleasedUnits, Timestamp.from(now));
        if (rows == 1) {
            return;
        }
        Boolean identical = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_trade_settlement_sides
                     WHERE product_line = ?
                       AND symbol = ?
                       AND trade_id = ?
                       AND participant_role = ?
                       AND taker_user_id = ?
                       AND maker_user_id = ?
                       AND command_id = ?
                       AND order_id = ?
                       AND order_margin_consumed_units = ?
                       AND order_margin_released_units = ?
                )
                """, Boolean.class, productLine.name(), trade.symbol(), trade.tradeId(), role.name(),
                trade.takerUserId(), trade.makerUserId(), commandId, orderId,
                orderMarginConsumedUnits, orderMarginReleasedUnits);
        if (!Boolean.TRUE.equals(identical)) {
            throw new IllegalStateException("failed to complete trade side "
                    + productLine + ":" + trade.symbol() + ":" + trade.tradeId() + ":" + role);
        }
    }
}
