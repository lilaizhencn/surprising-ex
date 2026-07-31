package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.LiquidationFeeContext;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平订单上下文单表仓储。 */
@Repository
public class LiquidationOrderContextRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationOrderContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LiquidationFeeContext> findFeeContext(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT liquidation_order_id, candidate_id, liquidation_fee_rate_ppm
                  FROM liquidation_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status IN ('SUBMITTED', 'PARTIALLY_FILLED', 'FILLED')
                """, (rs, rowNum) -> new LiquidationFeeContext(
                        rs.getLong("liquidation_order_id"),
                        rs.getLong("candidate_id"),
                        rs.getLong("liquidation_fee_rate_ppm")), orderId, userId, symbol)
                .stream().findFirst();
    }
}
