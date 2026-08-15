package com.surprising.price.index.repository;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_index_ticks} 表。 */
@Repository
public class IndexPriceTickRepository {

    private static final String INSERT_SQL = """
            INSERT INTO price_index_ticks (
                symbol, sequence, index_price, status, component_count, valid_component_count,
                total_configured_weight, event_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, sequence) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndexPriceTickRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveBatch(List<IndexPriceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                IndexPriceEvent event = events.get(index);
                statement.setString(1, event.symbol());
                statement.setLong(2, event.sequence());
                statement.setBigDecimal(3, event.indexPrice());
                statement.setString(4, event.status().name());
                statement.setInt(5, event.componentCount());
                statement.setInt(6, event.validComponentCount());
                statement.setBigDecimal(7, event.totalConfiguredWeight());
                statement.setTimestamp(8, Timestamp.from(event.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    public List<IndexPriceTick> history(String symbol, Instant startTime, Instant endTime, int limit) {
        return jdbcTemplate.query("""
                SELECT symbol, sequence, index_price, status, component_count, valid_component_count, event_time
                  FROM price_index_ticks
                 WHERE symbol = ?
                   AND event_time >= ?
                   AND event_time < ?
                   AND index_price IS NOT NULL
                 ORDER BY event_time ASC
                 LIMIT ?
                """, (rs, rowNum) -> new IndexPriceTick(
                        rs.getString("symbol"),
                        rs.getLong("sequence"),
                        rs.getBigDecimal("index_price"),
                        PriceStatus.valueOf(rs.getString("status")),
                        rs.getInt("component_count"),
                        rs.getInt("valid_component_count"),
                        rs.getTimestamp("event_time").toInstant()),
                symbol, Timestamp.from(startTime), Timestamp.from(endTime), limit);
    }

    public List<TickKey> findExpiredForDeletion(Instant cutoff, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT symbol, sequence
                  FROM price_index_ticks
                 WHERE event_time < ?
                 ORDER BY event_time ASC, symbol ASC, sequence ASC
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new TickKey(
                rs.getString("symbol"),
                rs.getLong("sequence")), Timestamp.from(cutoff), limit);
    }

    public int deleteByKeys(List<TickKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        List<Object> args = new java.util.ArrayList<>(keys.size() * 2);
        return jdbcTemplate.update("""
                DELETE FROM price_index_ticks
                 WHERE (symbol, sequence) IN (%s)
                """.formatted(tuplePredicate(keys, args)), args.toArray());
    }

    private String tuplePredicate(List<TickKey> keys, List<Object> args) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?)");
            TickKey key = keys.get(index);
            args.add(key.symbol());
            args.add(key.sequence());
        }
        return sql.toString();
    }

    public record IndexPriceTick(String symbol,
                                 long sequence,
                                 java.math.BigDecimal indexPrice,
                                 PriceStatus status,
                                 int componentCount,
                                 int validComponentCount,
                                 Instant eventTime) {
    }

    public record TickKey(String symbol, long sequence) {
    }
}
