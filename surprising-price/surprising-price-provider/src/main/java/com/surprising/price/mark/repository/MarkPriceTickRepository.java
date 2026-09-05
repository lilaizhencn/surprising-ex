package com.surprising.price.mark.repository;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_mark_ticks} 表。 */
@Repository
public class MarkPriceTickRepository {

    private static final String INSERT_SQL = """
            INSERT INTO price_mark_ticks (
                product_line, symbol, instrument_version, sequence, mark_price, mark_price_units,
                mark_price_ticks, index_price, price1, price2, last_trade_price,
                best_bid_price, best_ask_price, funding_rate, next_funding_time, time_until_funding_seconds,
                basis_average, basis_window_seconds, clamp_low, clamp_high, status, event_time, published_at,
                calculation_inputs
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public MarkPriceTickRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveBatch(List<MarkPriceAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                MarkPriceAuditRecord record = records.get(index);
                MarkPriceEvent event = record.event().result();
                statement.setString(1, event.productLine().name());
                statement.setString(2, event.symbol());
                statement.setLong(3, event.instrumentVersion());
                statement.setLong(4, event.sequence());
                statement.setBigDecimal(5, event.markPrice());
                statement.setLong(6, event.markPriceUnits());
                statement.setLong(7, event.markPriceTicks());
                statement.setBigDecimal(8, event.indexPrice());
                statement.setBigDecimal(9, event.price1());
                statement.setBigDecimal(10, event.price2());
                statement.setBigDecimal(11, event.lastTradePrice());
                statement.setBigDecimal(12, event.bestBidPrice());
                statement.setBigDecimal(13, event.bestAskPrice());
                statement.setBigDecimal(14, event.fundingRate());
                statement.setTimestamp(15, Timestamp.from(event.nextFundingTime()));
                statement.setLong(16, event.timeUntilFundingSeconds());
                statement.setBigDecimal(17, event.basisAverage());
                statement.setLong(18, event.basisWindowSeconds());
                statement.setBigDecimal(19, event.clampLow());
                statement.setBigDecimal(20, event.clampHigh());
                statement.setString(21, event.status().name());
                statement.setTimestamp(22, Timestamp.from(event.eventTime()));
                statement.setTimestamp(23, Timestamp.from(event.publishedAt()));
                statement.setString(24, record.payloadJson());
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    public int deleteBefore(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                DELETE FROM price_mark_ticks
                 WHERE ctid IN (
                    SELECT ctid
                      FROM price_mark_ticks
                     WHERE event_time < ?
                     LIMIT ?
                 )
                """, Timestamp.from(cutoff), batchSize);
    }

    public List<MarkPriceResponse> history(String symbol, Instant startTime, Instant endTime, int limit) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM price_mark_ticks
                 WHERE symbol = ?
                   AND event_time >= ?
                   AND event_time < ?
                 ORDER BY event_time ASC
                 LIMIT ?
                """, (rs, rowNum) -> new MarkPriceResponse(
                        rs.getString("symbol"),
                        rs.getBigDecimal("mark_price"),
                        rs.getLong("mark_price_units"),
                        rs.getBigDecimal("index_price"),
                        rs.getBigDecimal("price1"),
                        rs.getBigDecimal("price2"),
                        rs.getBigDecimal("last_trade_price"),
                        rs.getBigDecimal("best_bid_price"),
                        rs.getBigDecimal("best_ask_price"),
                        rs.getBigDecimal("funding_rate"),
                        rs.getTimestamp("next_funding_time").toInstant(),
                        rs.getLong("time_until_funding_seconds"),
                        rs.getBigDecimal("basis_average"),
                        rs.getLong("basis_window_seconds"),
                        rs.getBigDecimal("clamp_low"),
                        rs.getBigDecimal("clamp_high"),
                        rs.getLong("sequence"),
                        PriceStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("event_time").toInstant()),
                symbol, Timestamp.from(startTime), Timestamp.from(endTime), limit);
    }
}
