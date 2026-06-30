package com.surprising.candlestick.provider.repository;

import com.surprising.candlestick.api.model.CandleResponse;
import com.surprising.candlestick.api.model.CandleStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
        String sql = """
                SELECT %s
                  FROM candlestick_candles
                 WHERE symbol = ?
                   AND period = ?
                   AND open_time >= ?
                   AND open_time < ?
                 ORDER BY open_time ASC
                 LIMIT ?
                """.formatted(SELECT_COLUMNS);
        return jdbcTemplate.query(sql, CANDLE_ROW_MAPPER, symbol, period,
                java.sql.Timestamp.from(startTime), java.sql.Timestamp.from(endTime), limit);
    }

    public Optional<CandleResponse> findLatest(String symbol, String period) {
        String sql = """
                SELECT %s
                  FROM candlestick_candles
                 WHERE symbol = ?
                   AND period = ?
                 ORDER BY open_time DESC
                 LIMIT 1
                """.formatted(SELECT_COLUMNS);
        List<CandleResponse> rows = jdbcTemplate.query(sql, CANDLE_ROW_MAPPER, symbol, period);
        return rows.stream().findFirst();
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
