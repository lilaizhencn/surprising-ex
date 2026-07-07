package com.surprising.trading.order.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.ReduceOnlyPositionLookup;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReduceOnlyPositionRepository implements ReduceOnlyPositionLookup {

    private final JdbcTemplate jdbcTemplate;

    public ReduceOnlyPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode,
                                                       PositionSide positionSide) {
        return lockedPosition(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, positionSide);
    }

    @Override
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

    @Override
    public long lockedOpenReduceOnlySteps(long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          PositionSide positionSide,
                                          OrderSide closeSide) {
        return lockedOpenReduceOnlySteps(ProductLine.LINEAR_PERPETUAL, userId, symbol, marginMode, instrumentVersion,
                positionSide, closeSide);
    }

    @Override
    public long lockedOpenReduceOnlySteps(ProductLine productLine,
                                          long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          PositionSide positionSide,
                                          OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT remaining_quantity_steps
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND position_side = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("remaining_quantity_steps"),
                productLine(productLine).name(), userId, symbol, MarginMode.defaultIfNull(marginMode).name(),
                PositionSide.defaultIfNull(positionSide).name(), instrumentVersion, closeSide.name())
                .stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
    }

    private ProductLine productLine(ProductLine productLine) {
        return productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }
}
