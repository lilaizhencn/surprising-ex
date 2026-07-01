package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.OrderSide;
import java.time.Duration;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingProtectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
        return jdbcTemplate.query("""
                SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_ticks
                  FROM price_mark_ticks m
                  JOIN instruments i ON i.symbol = m.symbol AND i.version = ?
                 WHERE m.symbol = ?
                   AND m.event_time >= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY m.event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> rs.getLong("mark_ticks"), instrumentVersion, symbol, maxAge.toMillis())
                .stream()
                .mapToLong(Long::longValue)
                .findFirst();
    }

    public boolean wouldSelfTrade(long userId,
                                  String symbol,
                                  long instrumentVersion,
                                  OrderSide side,
                                  long effectivePriceTicks) {
        OrderSide oppositeSide = side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE user_id = ?
                       AND symbol = ?
                       AND instrument_version = ?
                       AND side = ?
                       AND remaining_quantity_steps > 0
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                       AND (
                           (? = 'BUY' AND price_ticks <= ?)
                           OR (? = 'SELL' AND price_ticks >= ?)
                       )
                )
                """, Boolean.class, userId, symbol, instrumentVersion, oppositeSide.name(), side.name(), effectivePriceTicks,
                side.name(), effectivePriceTicks);
        return Boolean.TRUE.equals(exists);
    }

    public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol, long instrumentVersion, long orderId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE symbol = ?
                       AND order_id <> ?
                       AND instrument_version <> ?
                       AND remaining_quantity_steps > 0
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                )
                """, Boolean.class, symbol, orderId, instrumentVersion);
        return Boolean.TRUE.equals(exists);
    }
}
