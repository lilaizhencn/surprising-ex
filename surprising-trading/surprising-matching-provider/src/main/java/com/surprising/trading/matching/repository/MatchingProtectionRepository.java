package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.matching.config.MatchingProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingProtectionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
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
        StringBuilder sql = new StringBuilder("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE user_id = ?
                       AND symbol = ?
                       AND instrument_version = ?
                       AND side = ?
                       AND remaining_quantity_steps > 0
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                """);
        List<Object> args = new ArrayList<>(List.of(userId, symbol, instrumentVersion, oppositeSide.name()));
        appendProductLinePredicate(sql, args);
        sql.append("""
                       AND (
                           (? = 'BUY' AND price_ticks <= ?)
                           OR (? = 'SELL' AND price_ticks >= ?)
                       )
                )
                """);
        args.add(side.name());
        args.add(effectivePriceTicks);
        args.add(side.name());
        args.add(effectivePriceTicks);
        Boolean exists = jdbcTemplate.queryForObject(sql.toString(), Boolean.class, args.toArray());
        return Boolean.TRUE.equals(exists);
    }

    public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol, long instrumentVersion, long orderId) {
        StringBuilder sql = new StringBuilder("""
                SELECT EXISTS (
                    SELECT 1
                      FROM trading_orders
                     WHERE symbol = ?
                       AND order_id <> ?
                       AND instrument_version <> ?
                       AND remaining_quantity_steps > 0
                       AND status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                """);
        List<Object> args = new ArrayList<>(List.of(symbol, orderId, instrumentVersion));
        appendProductLinePredicate(sql, args);
        sql.append("""
                )
                """);
        Boolean exists = jdbcTemplate.queryForObject(sql.toString(), Boolean.class, args.toArray());
        return Boolean.TRUE.equals(exists);
    }

    private void appendProductLinePredicate(StringBuilder sql, List<Object> args) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (kafka.isProductTopicsEnabled()) {
            sql.append("                       AND product_line = ?\n");
            args.add(kafka.getProductLine().name());
        }
    }
}
