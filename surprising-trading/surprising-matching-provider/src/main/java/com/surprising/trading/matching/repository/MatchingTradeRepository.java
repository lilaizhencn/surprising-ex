package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.MarketTickerSummary;
import com.surprising.trading.matching.config.MatchingProperties;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_match_trades} 表。 */
@Repository
public class MatchingTradeRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingProperties properties;

    public MatchingTradeRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MatchingProperties());
    }

    @Autowired
    public MatchingTradeRepository(JdbcTemplate jdbcTemplate, MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void saveBatch(List<MatchTradeEvent> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_match_trades (
                    trade_id, command_id, product_line, symbol, taker_order_id, taker_instrument_version,
                    taker_user_id, taker_side, taker_margin_mode, taker_position_side,
                    maker_order_id, maker_instrument_version,
                    maker_user_id, maker_margin_mode, maker_position_side,
                    taker_fee_rate_ppm, maker_fee_rate_ppm, price_ticks, quantity_steps,
                    taker_order_completed, maker_order_completed, trace_id, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (product_line, symbol, trade_id) DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                MatchTradeEvent trade = trades.get(index);
                statement.setLong(1, trade.tradeId());
                statement.setLong(2, trade.commandId());
                statement.setString(3, productLine());
                statement.setString(4, trade.symbol());
                statement.setLong(5, trade.takerOrderId());
                statement.setLong(6, trade.takerInstrumentVersion());
                statement.setLong(7, trade.takerUserId());
                statement.setString(8, trade.takerSide().name());
                statement.setString(9, trade.takerMarginMode().name());
                statement.setString(10, trade.takerPositionSide().name());
                statement.setLong(11, trade.makerOrderId());
                statement.setLong(12, trade.makerInstrumentVersion());
                statement.setLong(13, trade.makerUserId());
                statement.setString(14, trade.makerMarginMode().name());
                statement.setString(15, trade.makerPositionSide().name());
                statement.setLong(16, trade.takerFeeRatePpm());
                statement.setLong(17, trade.makerFeeRatePpm());
                statement.setLong(18, trade.priceTicks());
                statement.setLong(19, trade.quantitySteps());
                statement.setBoolean(20, trade.takerOrderCompleted());
                statement.setBoolean(21, trade.makerOrderCompleted());
                statement.setString(22, trade.traceId());
                statement.setTimestamp(23, Timestamp.from(trade.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return trades.size();
            }
        });
        requireCompleteBatch(rows, trades.size());
    }

    public MarketTickerSummary summary(String symbol, Instant from, Instant to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("ticker summary request is invalid");
        }
        return jdbcTemplate.queryForObject("""
                WITH recent AS (
                    SELECT trade_id, price_ticks, quantity_steps, event_time
                      FROM trading_match_trades
                     WHERE product_line = ?
                       AND symbol = ?
                       AND event_time >= ?
                       AND event_time <= ?
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
                """, (result, rowNum) -> new MarketTickerSummary(
                        symbol.trim().toUpperCase(java.util.Locale.ROOT),
                        result.getLong("first_trade_id"),
                        result.getLong("last_trade_id"),
                        result.getLong("trade_count"),
                        result.getLong("open_price_ticks"),
                        result.getLong("high_price_ticks"),
                        result.getLong("low_price_ticks"),
                        result.getLong("last_price_ticks"),
                        result.getBigDecimal("volume_steps"),
                        result.getBigDecimal("quote_volume_ticks_steps"),
                        result.getBigDecimal("last_quantity_steps"),
                        timestamp(result.getTimestamp("open_time")),
                        timestamp(result.getTimestamp("close_time"))),
                productLine(), symbol.trim().toUpperCase(java.util.Locale.ROOT),
                Timestamp.from(from), Timestamp.from(to));
    }

    private Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String productLine() {
        MatchingProperties.Kafka kafka = properties.getKafka();
        return kafka.isProductTopicsEnabled()
                ? kafka.getProductLine().name()
                : ProductLine.LINEAR_PERPETUAL.name();
    }

    private void requireCompleteBatch(int[] rows, int expected) {
        if (rows == null || rows.length != expected) {
            throw new IllegalStateException("failed to write match trades");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write match trades");
            }
        }
    }
}
