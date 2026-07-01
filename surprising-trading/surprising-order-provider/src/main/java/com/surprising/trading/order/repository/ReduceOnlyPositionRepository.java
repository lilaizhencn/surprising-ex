package com.surprising.trading.order.repository;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
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
    public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode) {
        return jdbcTemplate.query("""
                SELECT signed_quantity_steps, instrument_version
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ? AND margin_mode = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new ReduceOnlyPosition(
                rs.getLong("signed_quantity_steps"),
                rs.getLong("instrument_version")), userId, symbol, MarginMode.defaultIfNull(marginMode).name())
                .stream()
                .findFirst();
    }

    @Override
    public long lockedOpenReduceOnlySteps(long userId,
                                          String symbol,
                                          MarginMode marginMode,
                                          long instrumentVersion,
                                          OrderSide closeSide) {
        return jdbcTemplate.query("""
                SELECT remaining_quantity_steps
                  FROM trading_orders
                 WHERE user_id = ?
                   AND symbol = ?
                   AND margin_mode = ?
                   AND instrument_version = ?
                   AND side = ?
                   AND reduce_only = TRUE
                   AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("remaining_quantity_steps"),
                userId, symbol, MarginMode.defaultIfNull(marginMode).name(), instrumentVersion, closeSide.name())
                .stream()
                .mapToLong(Long::longValue)
                .reduce(0L, Math::addExact);
    }
}
