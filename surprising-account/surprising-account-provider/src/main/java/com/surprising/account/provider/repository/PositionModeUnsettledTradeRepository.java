package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 检查持仓模式切换前是否仍有未完成的成交侧结算。
 *
 * <p>不可拆原因：成交是否结算必须在同一数据库快照中关联 trading_match_trades 与
 * account_trade_settlement_sides；拆成两次查询会在结算并发窗口错误允许或拒绝模式切换。
 * 该查询只服务在线账户状态安全检查，不提供后台时间线、资金对账或运营报表。</p>
 */
@Repository
public class PositionModeUnsettledTradeRepository {

    private final JdbcTemplate jdbcTemplate;

    public PositionModeUnsettledTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(ProductLine productLine, long userId) {
        Boolean exists = jdbcTemplate.queryForObject("""
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
                """, Boolean.class, productLine.name(), userId, userId);
        return Boolean.TRUE.equals(exists);
    }
}
