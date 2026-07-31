package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只减仓校验的持仓仓储，只负责 {@code account_positions} 表。
 */
@Repository
public class ReduceOnlyPositionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReduceOnlyPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode,
                                                       PositionSide positionSide) {
        return lockedPosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    public Optional<ReduceOnlyPosition> lockedPosition(ProductLine productLine,
                                                       long userId,
                                                       String symbol,
                                                       MarginMode marginMode,
                                                       PositionSide positionSide) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps, instrument_version
                  FROM account_positions
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ReduceOnlyPosition(
                rs.getLong("signed_quantity_steps"),
                rs.getLong("instrument_version")), productLine(productLine).name(), userId, symbol,
                MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name())
                .stream()
                .findFirst();
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }
}
