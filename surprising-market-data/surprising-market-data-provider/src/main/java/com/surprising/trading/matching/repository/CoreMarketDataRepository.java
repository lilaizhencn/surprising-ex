package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.UserMatchTradeQueryResponse;
import com.surprising.trading.api.model.UserMatchTradeResponse;
import com.surprising.trading.matching.config.MatchingProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoreMarketDataRepository {

    private static final long TRADE_SEQUENCE_MULTIPLIER = 1_000_000L;

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public CoreMarketDataRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public MarketTickerSummary summary(String symbol, Instant from, Instant to) {
        String normalized = normalizeSymbol(symbol);
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("ticker summary request is invalid");
        }
        return jdbcTemplate.queryForObject("""
                WITH recent AS (
                    SELECT export_sequence * 1000000 + execution_index AS trade_id,
                           price_ticks, quantity_steps,
                           to_timestamp(occurred_at_epoch_ms / 1000.0) AS event_time
                      FROM core_execution_projection
                     WHERE product_line = ? AND symbol = ?
                       AND occurred_at_epoch_ms >= ? AND occurred_at_epoch_ms <= ?
                )
                SELECT COALESCE((SELECT trade_id FROM recent ORDER BY event_time, trade_id LIMIT 1), 0) AS first_trade_id,
                       COALESCE((SELECT trade_id FROM recent ORDER BY event_time DESC, trade_id DESC LIMIT 1), 0) AS last_trade_id,
                       COUNT(*) AS trade_count,
                       COALESCE((SELECT price_ticks FROM recent ORDER BY event_time, trade_id LIMIT 1), 0) AS open_price_ticks,
                       COALESCE(MAX(price_ticks), 0) AS high_price_ticks,
                       COALESCE(MIN(price_ticks), 0) AS low_price_ticks,
                       COALESCE((SELECT price_ticks FROM recent ORDER BY event_time DESC, trade_id DESC LIMIT 1), 0) AS last_price_ticks,
                       COALESCE(SUM(quantity_steps::NUMERIC), 0) AS volume_steps,
                       COALESCE(SUM(price_ticks::NUMERIC * quantity_steps::NUMERIC), 0) AS quote_volume_ticks_steps,
                       COALESCE((SELECT quantity_steps::NUMERIC FROM recent ORDER BY event_time DESC, trade_id DESC LIMIT 1), 0) AS last_quantity_steps,
                       MIN(event_time) AS open_time,
                       MAX(event_time) AS close_time
                  FROM recent
                """, (result, rowNum) -> new MarketTickerSummary(normalized,
                        result.getLong("first_trade_id"), result.getLong("last_trade_id"),
                        result.getLong("trade_count"), result.getLong("open_price_ticks"),
                        result.getLong("high_price_ticks"), result.getLong("low_price_ticks"),
                        result.getLong("last_price_ticks"), result.getBigDecimal("volume_steps"),
                        result.getBigDecimal("quote_volume_ticks_steps"),
                        result.getBigDecimal("last_quantity_steps"), timestamp(result.getTimestamp("open_time")),
                        timestamp(result.getTimestamp("close_time"))), productLine().name(), normalized,
                from.toEpochMilli(), to.toEpochMilli());
    }

    public UserMatchTradeQueryResponse userTrades(long userId, String symbol, int requestedLimit, String cursor) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        String normalized = normalizeSymbol(symbol);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        Long beforeTradeId = decodeCursor(cursor);
        StringBuilder sql = new StringBuilder("""
                SELECT export_sequence, execution_index, taker_order_id, maker_order_id,
                       taker_user_id, maker_user_id, taker_side, taker_fee_rate_ppm,
                       maker_fee_rate_ppm, price_ticks, quantity_steps, occurred_at_epoch_ms
                  FROM core_execution_projection
                 WHERE product_line = ? AND symbol = ?
                   AND (taker_user_id = ? OR maker_user_id = ?)
                """);
        List<Object> args = new ArrayList<>(List.of(productLine().name(), normalized, userId, userId));
        if (beforeTradeId != null) {
            sql.append(" AND (export_sequence * 1000000 + execution_index) < ?");
            args.add(beforeTradeId);
        }
        sql.append(" ORDER BY export_sequence DESC, execution_index DESC LIMIT ?");
        args.add(limit + 1);
        List<UserMatchTradeResponse> fetched = jdbcTemplate.query(sql.toString(), (result, rowNum) -> {
            long tradeId = tradeId(result.getLong("export_sequence"), result.getInt("execution_index"));
            boolean taker = result.getLong("taker_user_id") == userId;
            OrderSide takerSide = OrderSide.valueOf(result.getString("taker_side"));
            return new UserMatchTradeResponse(tradeId,
                    result.getLong(taker ? "taker_order_id" : "maker_order_id"), normalized,
                    taker ? takerSide : opposite(takerSide), result.getLong("price_ticks"),
                    result.getLong("quantity_steps"),
                    result.getLong(taker ? "taker_fee_rate_ppm" : "maker_fee_rate_ppm"),
                    Instant.ofEpochMilli(result.getLong("occurred_at_epoch_ms")));
        }, args.toArray());
        boolean hasMore = fetched.size() > limit;
        List<UserMatchTradeResponse> rows = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore && !rows.isEmpty() ? encodeCursor(rows.getLast().tradeId()) : null;
        return new UserMatchTradeQueryResponse(rows, nextCursor, hasMore, "tradeId.desc", limit);
    }

    private ProductLine productLine() {
        ProductLine productLine = properties.getKafka().getProductLine();
        if (productLine == null) throw new IllegalStateException("matching product line is required");
        return productLine;
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        return symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static long tradeId(long exportSequence, int executionIndex) {
        return Math.addExact(Math.multiplyExact(exportSequence, TRADE_SEQUENCE_MULTIPLIER), executionIndex);
    }

    private static OrderSide opposite(OrderSide side) {
        return side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(new String(Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid trade cursor", exception);
        }
    }

    private static String encodeCursor(long tradeId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(tradeId).getBytes(StandardCharsets.UTF_8));
    }

}
