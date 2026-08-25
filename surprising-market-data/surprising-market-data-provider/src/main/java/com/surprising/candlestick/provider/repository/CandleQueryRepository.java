package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.api.model.CandleStatus;
import com.surprising.candlestick.api.model.CandlePeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 只负责 {@code candlestick_candles} 表的只读查询。
 */
@Repository
public class CandleQueryRepository {

    private static final String SELECT_COLUMNS = """
            symbol, period, open_time, close_time,
            open_price, high_price, low_price, close_price,
            base_volume, quote_volume, trade_count,
            first_trade_id, last_trade_id, first_sequence, last_sequence,
            status, updated_at
            """;

    private static final RowMapper<CandleResponse> CANDLE_ROW_MAPPER = new CandleRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public CandleQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CandleResponse> findRange(String symbol, String period, Instant startTime, Instant endTime, int limit) {
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        if (candlePeriod != CandlePeriod.M1) {
            return findRollupRange(symbol, candlePeriod, startTime, endTime, limit);
        }
        String sql = """
                SELECT %s
                  FROM candlestick_candles
                 WHERE symbol = ?
                   AND period = ?
                   AND status = 'CLOSED'
                   AND open_time >= ?
                   AND open_time < ?
                 ORDER BY open_time ASC
                 LIMIT ?
                """.formatted(SELECT_COLUMNS);
        return jdbcTemplate.query(sql, CANDLE_ROW_MAPPER, symbol, period,
                java.sql.Timestamp.from(startTime), java.sql.Timestamp.from(endTime), limit);
    }

    public Optional<CandleResponse> findLatest(String symbol, String period) {
        CandlePeriod candlePeriod = CandlePeriod.fromCode(period);
        if (candlePeriod != CandlePeriod.M1) {
            return findLatestRollup(symbol, candlePeriod);
        }
        String sql = """
                SELECT %s
                  FROM candlestick_candles
                 WHERE symbol = ?
                   AND period = ?
                   AND status = 'CLOSED'
                 ORDER BY open_time DESC
                 LIMIT 1
                """.formatted(SELECT_COLUMNS);
        List<CandleResponse> rows = jdbcTemplate.query(sql, CANDLE_ROW_MAPPER, symbol, period);
        return rows.stream().findFirst();
    }

    private List<CandleResponse> findRollupRange(String symbol, CandlePeriod period,
                                                  Instant startTime, Instant endTime, int limit) {
        long bucketSeconds = period.duration().toSeconds();
        Instant inputStart = period.floor(startTime);
        Instant endFloor = period.floor(endTime);
        Instant inputEnd = endFloor.equals(endTime) ? endTime : endFloor.plus(period.duration());
        String sql = rollupSql("AND open_time >= ? AND open_time < ?",
                "AND open_time >= ? AND open_time < ? ORDER BY open_time ASC LIMIT ?");
        return jdbcTemplate.query(sql, CANDLE_ROW_MAPPER, bucketSeconds, bucketSeconds, symbol,
                java.sql.Timestamp.from(inputStart), java.sql.Timestamp.from(inputEnd), period.code(),
                bucketSeconds, java.sql.Timestamp.from(startTime), java.sql.Timestamp.from(endTime), limit);
    }

    private Optional<CandleResponse> findLatestRollup(String symbol, CandlePeriod period) {
        long bucketSeconds = period.duration().toSeconds();
        String sql = rollupSql("", "ORDER BY open_time DESC LIMIT 1");
        List<CandleResponse> rows = jdbcTemplate.query(sql, CANDLE_ROW_MAPPER,
                bucketSeconds, bucketSeconds, symbol, period.code(), bucketSeconds);
        return rows.stream().findFirst();
    }

    private String rollupSql(String minuteBounds, String ordering) {
        return """
                WITH bucketed AS (
                    SELECT *, to_timestamp(floor(extract(epoch FROM open_time) / ?) * ?) AS bucket_open
                      FROM candlestick_candles
                     WHERE symbol = ?
                       AND period = '1m'
                       AND status = 'CLOSED'
                       %s
                ), rollups AS (
                    SELECT symbol,
                           ? AS period,
                           bucket_open AS open_time,
                           bucket_open + (? * interval '1 second') AS close_time,
                           (array_agg(open_price ORDER BY open_time ASC))[1] AS open_price,
                           max(high_price) AS high_price,
                           min(low_price) AS low_price,
                           (array_agg(close_price ORDER BY open_time DESC))[1] AS close_price,
                           sum(base_volume) AS base_volume,
                           sum(quote_volume) AS quote_volume,
                           sum(trade_count) AS trade_count,
                           (array_agg(first_trade_id ORDER BY open_time ASC)
                               FILTER (WHERE first_trade_id IS NOT NULL))[1] AS first_trade_id,
                           (array_agg(last_trade_id ORDER BY open_time DESC)
                               FILTER (WHERE last_trade_id IS NOT NULL))[1] AS last_trade_id,
                           (array_agg(first_sequence ORDER BY open_time ASC)
                               FILTER (WHERE first_sequence IS NOT NULL))[1] AS first_sequence,
                           (array_agg(last_sequence ORDER BY open_time DESC)
                               FILTER (WHERE last_sequence IS NOT NULL))[1] AS last_sequence,
                           'CLOSED' AS status,
                           max(updated_at) AS updated_at
                      FROM bucketed
                     GROUP BY symbol, bucket_open
                )
                SELECT %s
                  FROM rollups
                 WHERE close_time <= CURRENT_TIMESTAMP
                 %s
                """.formatted(minuteBounds, SELECT_COLUMNS, ordering);
    }

    private static final class CandleRowMapper implements RowMapper<CandleResponse> {
        @Override
        public CandleResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CandleResponse(
                    rs.getString("symbol"),
                    rs.getString("period"),
                    rs.getTimestamp("open_time").toInstant(),
                    rs.getTimestamp("close_time").toInstant(),
                    rs.getBigDecimal("open_price"),
                    rs.getBigDecimal("high_price"),
                    rs.getBigDecimal("low_price"),
                    rs.getBigDecimal("close_price"),
                    rs.getBigDecimal("base_volume"),
                    rs.getBigDecimal("quote_volume"),
                    rs.getLong("trade_count"),
                    rs.getString("first_trade_id"),
                    rs.getString("last_trade_id"),
                    getNullableLong(rs, "first_sequence"),
                    getNullableLong(rs, "last_sequence"),
                    CandleStatus.valueOf(rs.getString("status")),
                    rs.getTimestamp("updated_at").toInstant());
        }

        private Long getNullableLong(ResultSet rs, String column) throws SQLException {
            long value = rs.getLong(column);
            return rs.wasNull() ? null : value;
        }
    }
}
