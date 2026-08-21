package com.surprising.price.index.repository;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.SourceStatus;
import com.surprising.price.index.repository.IndexPriceTickRepository.TickKey;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_index_components} 表。 */
@Repository
public class IndexPriceComponentRepository {

    private static final String INSERT_SQL = """
            INSERT INTO price_index_components (
                symbol, sequence, source, source_symbol, price, bid_price, ask_price,
                configured_weight, effective_weight, status, reason, source_time, received_at, latency_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, sequence, source) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndexPriceComponentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveBatch(List<IndexPriceEvent> events) {
        List<IndexComponentRow> rows = rows(events);
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                IndexComponentRow row = rows.get(index);
                IndexComponentSnapshot component = row.component();
                statement.setString(1, row.event().symbol());
                statement.setLong(2, row.event().sequence());
                statement.setString(3, component.source());
                statement.setString(4, component.sourceSymbol());
                statement.setBigDecimal(5, component.price());
                statement.setBigDecimal(6, component.bidPrice());
                statement.setBigDecimal(7, component.askPrice());
                statement.setBigDecimal(8, component.configuredWeight());
                statement.setBigDecimal(9, component.effectiveWeight());
                statement.setString(10, component.status().name());
                statement.setString(11, component.reason());
                setTimestamp(statement, 12, component.sourceTime());
                setTimestamp(statement, 13, component.receivedAt());
                if (component.latencyMillis() == null) {
                    statement.setNull(14, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(14, component.latencyMillis());
                }
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }

    public List<IndexComponentSnapshot> find(String symbol, long sequence) {
        return jdbcTemplate.query("""
                SELECT source, source_symbol, price, bid_price, ask_price,
                       configured_weight, effective_weight, status, reason, source_time, received_at, latency_millis
                  FROM price_index_components
                 WHERE symbol = ?
                   AND sequence = ?
                 ORDER BY source ASC
                """, (rs, rowNum) -> new IndexComponentSnapshot(
                rs.getString("source"),
                rs.getString("source_symbol"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("bid_price"),
                rs.getBigDecimal("ask_price"),
                rs.getBigDecimal("configured_weight"),
                rs.getBigDecimal("effective_weight"),
                SourceStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                timestamp(rs.getTimestamp("source_time")),
                timestamp(rs.getTimestamp("received_at")),
                nullableLong(rs, "latency_millis"), null), symbol, sequence);
    }

    public int deleteByKeys(List<TickKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        List<Object> args = new ArrayList<>(keys.size() * 2);
        return jdbcTemplate.update("""
                DELETE FROM price_index_components
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

    private List<IndexComponentRow> rows(List<IndexPriceEvent> events) {
        List<IndexComponentRow> rows = new ArrayList<>();
        if (events == null) {
            return rows;
        }
        for (IndexPriceEvent event : events) {
            if (event.components() == null) {
                continue;
            }
            for (IndexComponentSnapshot component : event.components()) {
                rows.add(new IndexComponentRow(event, component));
            }
        }
        return rows;
    }

    private void setTimestamp(PreparedStatement statement, int index, Instant instant)
            throws java.sql.SQLException {
        if (instant == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private record IndexComponentRow(IndexPriceEvent event, IndexComponentSnapshot component) {
    }
}
