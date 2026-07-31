package com.surprising.trading.matching.repository;

import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.trading.api.model.OrderCommandEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.service.MatchingProtectionIndex;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_orders} 表的撮合前保护查询。 */
@Repository
public class MatchingProtectionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;
    private final LatestMarkPriceCache markPriceCache;
    private final MatchingProtectionIndex protectionIndex;

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties(), null, null);
    }

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this(jdbcTemplate, properties, null, null);
    }

    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate,
                                        MatchingProperties properties,
                                        LatestMarkPriceCache markPriceCache) {
        this(jdbcTemplate, properties, markPriceCache, null);
    }

    @Autowired
    public MatchingProtectionRepository(JdbcTemplate jdbcTemplate,
                                        MatchingProperties properties,
                                        LatestMarkPriceCache markPriceCache,
                                        MatchingProtectionIndex protectionIndex) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.markPriceCache = markPriceCache;
        this.protectionIndex = protectionIndex;
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
        if (protectionIndex != null && protectionIndex.ready()) {
            return protectionIndex.wouldSelfTrade(userId, symbol, instrumentVersion, side, effectivePriceTicks);
        }
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

    public Set<Long> commandsThatWouldSelfTrade(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        if (protectionIndex != null && protectionIndex.ready()) {
            return protectionIndex.commandsThatWouldSelfTrade(commands);
        }
        String values = String.join(", ", Collections.nCopies(commands.size(),
                "(?::BIGINT, ?::BIGINT, ?::TEXT, ?::BIGINT, ?::TEXT, ?::BIGINT)"));
        List<Object> args = new ArrayList<>(commands.size() * 6 + 1);
        for (OrderCommandEvent command : commands) {
            args.add(command.commandId());
            args.add(command.userId());
            args.add(command.symbol());
            args.add(command.instrumentVersion());
            args.add(command.side().name());
            args.add(command.priceTicks());
        }
        StringBuilder sql = new StringBuilder("""
                WITH input(command_id, user_id, symbol, instrument_version, side, effective_price_ticks) AS (
                    VALUES %s
                )
                SELECT input.command_id
                  FROM input
                 WHERE EXISTS (
                    SELECT 1
                      FROM trading_orders orders
                     WHERE orders.user_id = input.user_id
                       AND orders.symbol = input.symbol
                       AND orders.instrument_version = input.instrument_version
                       AND orders.side <> input.side
                       AND orders.remaining_quantity_steps > 0
                       AND orders.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                """.formatted(values));
        appendProductLinePredicate(sql, args, "orders");
        sql.append("""
                       AND (
                           (input.side = 'BUY' AND orders.price_ticks <= input.effective_price_ticks)
                           OR (input.side = 'SELL' AND orders.price_ticks >= input.effective_price_ticks)
                       )
                )
                """);
        Set<Long> commandIds = new LinkedHashSet<>();
        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs ->
                commandIds.add(rs.getLong("command_id")), args.toArray());
        return Set.copyOf(commandIds);
    }

    public boolean hasOpenOrdersWithDifferentInstrumentVersion(String symbol, long instrumentVersion, long orderId) {
        if (protectionIndex != null && protectionIndex.ready()) {
            return protectionIndex.hasOpenOrdersWithDifferentInstrumentVersion(symbol, instrumentVersion, orderId);
        }
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

    public Set<Long> commandsWithOpenOrdersAtDifferentInstrumentVersion(List<OrderCommandEvent> commands) {
        if (commands == null || commands.isEmpty()) {
            return Set.of();
        }
        if (protectionIndex != null && protectionIndex.ready()) {
            return protectionIndex.commandsWithOpenOrdersAtDifferentInstrumentVersion(commands);
        }
        String values = String.join(", ", Collections.nCopies(commands.size(),
                "(?::BIGINT, ?::TEXT, ?::BIGINT, ?::BIGINT)"));
        List<Object> args = new ArrayList<>(commands.size() * 4 + 1);
        for (OrderCommandEvent command : commands) {
            args.add(command.commandId());
            args.add(command.symbol());
            args.add(command.instrumentVersion());
            args.add(command.orderId());
        }
        StringBuilder sql = new StringBuilder("""
                WITH input(command_id, symbol, instrument_version, order_id) AS (
                    VALUES %s
                )
                SELECT input.command_id
                  FROM input
                 WHERE EXISTS (
                    SELECT 1
                      FROM trading_orders orders
                     WHERE orders.symbol = input.symbol
                       AND orders.order_id <> input.order_id
                       AND orders.instrument_version <> input.instrument_version
                       AND orders.remaining_quantity_steps > 0
                       AND orders.status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                """.formatted(values));
        appendProductLinePredicate(sql, args, "orders");
        sql.append("""
                )
                """);
        Set<Long> commandIds = new LinkedHashSet<>();
        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) rs ->
                commandIds.add(rs.getLong("command_id")), args.toArray());
        return Set.copyOf(commandIds);
    }

    private void appendProductLinePredicate(StringBuilder sql, List<Object> args) {
        appendProductLinePredicate(sql, args, null);
    }

    private void appendProductLinePredicate(StringBuilder sql, List<Object> args, String alias) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (kafka.isProductTopicsEnabled()) {
            sql.append("                       AND ");
            if (alias != null && !alias.isBlank()) {
                sql.append(alias).append('.');
            }
            sql.append("product_line = ?\n");
            args.add(kafka.getProductLine().name());
        }
    }
}
