package com.surprising.trading.matching.repository;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.price.consumer.LatestMarkPriceCache;
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
    private final LatestMarkPriceCache markPriceCache;

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties(), null);
    }

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this(jdbcTemplate, properties, null);
    }

    @Autowired
    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate,
                                        MatchingProperties properties,
                                        LatestMarkPriceCache markPriceCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.markPriceCache = markPriceCache;
    }

    public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, Duration maxAge) {
        if (markPriceCache == null) {
            return OptionalLong.empty();
        }
        var event = markPriceCache.fresh(symbol, maxAge)
                .filter(value -> value.instrumentVersion() == instrumentVersion)
                .filter(value -> value.markPriceTicks() > 0);
        return event.isPresent() ? OptionalLong.of(event.orElseThrow().markPriceTicks()) : OptionalLong.empty();
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
