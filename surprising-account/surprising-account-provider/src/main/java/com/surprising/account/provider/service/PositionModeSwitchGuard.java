package com.surprising.account.provider.service;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PositionModeSwitchGuard {

    private final JdbcTemplate jdbcTemplate;

    public PositionModeSwitchGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lock(ProductLine productLine, long userId) {
        jdbcTemplate.query("""
                SELECT pg_advisory_xact_lock(hashtext('position-mode'), hashtext(?))
                """, rs -> null, productLine.name() + ":" + userId);
    }

    public void requireSwitchable(ProductLine productLine, long userId) {
        String productLineName = productLine.name();
        Boolean hasPositions = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM account_positions p
                     WHERE p.user_id = ?
                       AND p.signed_quantity_steps <> 0
                       AND p.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasPositions)) {
            throw new IllegalStateException("position mode switch requires no open positions");
        }
        Boolean hasOpenOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders o
                     WHERE o.user_id = ?
                       AND o.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND o.remaining_quantity_steps > 0
                       AND o.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasOpenOrders)) {
            throw new IllegalStateException("position mode switch requires no active orders");
        }
        Boolean hasTriggerOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_trigger_orders t
                     WHERE t.user_id = ?
                       AND t.status IN ('PENDING', 'TRIGGERING')
                       AND t.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasTriggerOrders)) {
            throw new IllegalStateException("position mode switch requires no pending trigger orders");
        }
        Boolean hasAlgoOrders = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_algo_orders a
                     WHERE a.user_id = ?
                       AND a.status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
                       AND a.product_line = ?
                )
                """, Boolean.class, userId, productLineName);
        if (Boolean.TRUE.equals(hasAlgoOrders)) {
            throw new IllegalStateException("position mode switch requires no active algo orders");
        }
        Boolean hasUnsettledTrades = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_match_trades mt
                     WHERE mt.product_line = ?
                       AND (
                           (mt.taker_user_id = ? AND NOT EXISTS (
                               SELECT 1
                                 FROM account_trade_settlement_sides s
                                WHERE s.product_line = mt.product_line
                                  AND s.symbol = mt.symbol
                                  AND s.trade_id = mt.trade_id
                                  AND s.participant_role = 'TAKER'
                           ))
                           OR
                           (mt.maker_user_id = ? AND NOT EXISTS (
                               SELECT 1
                                 FROM account_trade_settlement_sides s
                                WHERE s.product_line = mt.product_line
                                  AND s.symbol = mt.symbol
                                  AND s.trade_id = mt.trade_id
                                  AND s.participant_role = 'MAKER'
                           ))
                       )
                )
                """, Boolean.class, productLineName, userId, userId);
        if (Boolean.TRUE.equals(hasUnsettledTrades)) {
            throw new IllegalStateException("position mode switch requires all matched trades to be settled");
        }
    }
}
