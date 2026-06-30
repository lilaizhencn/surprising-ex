package com.surprising.price.index.repository;

import com.surprising.price.api.model.IndexComponentSnapshot;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.SourceStatus;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IndexPriceRepository {

    private static final String INSERT_TICK_SQL = """
            INSERT INTO price_index_ticks (
                symbol, sequence, index_price, status, component_count, valid_component_count,
                total_configured_weight, event_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, sequence) DO NOTHING
            """;

    private static final String INSERT_COMPONENT_SQL = """
            INSERT INTO price_index_components (
                symbol, sequence, source, source_symbol, price, bid_price, ask_price,
                configured_weight, effective_weight, status, reason, source_time, received_at, latency_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, sequence, source) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndexPriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean acquireLease(String module, String symbol, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        String sql = """
                INSERT INTO price_symbol_leases (module, symbol, owner_id, lease_until, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (module, symbol) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    updated_at = EXCLUDED.updated_at
                WHERE price_symbol_leases.owner_id = EXCLUDED.owner_id
                   OR price_symbol_leases.lease_until <= EXCLUDED.updated_at
                RETURNING TRUE
                """;
        List<Boolean> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getBoolean(1),
                module, symbol, ownerId, Timestamp.from(leaseUntil), Timestamp.from(now));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    public long nextSequence(String module, String symbol) {
        String sql = """
                INSERT INTO price_symbol_sequences (module, symbol, sequence, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (module, symbol) DO UPDATE SET
                    sequence = price_symbol_sequences.sequence + 1,
                    updated_at = now()
                RETURNING sequence
                """;
        Long sequence = jdbcTemplate.queryForObject(sql, Long.class, module, symbol);
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate sequence for " + module + ":" + symbol);
        }
        return sequence;
    }

    public void save(IndexPriceEvent event) {
        jdbcTemplate.update(INSERT_TICK_SQL, event.symbol(), event.sequence(), event.indexPrice(), event.status().name(),
                event.componentCount(), event.validComponentCount(), event.totalConfiguredWeight(),
                Timestamp.from(event.eventTime()));
        saveComponents(event);
    }

    public Optional<IndexPriceResponse> latest(String symbol) {
        String sql = """
                SELECT symbol, sequence, index_price, status, component_count, valid_component_count, event_time
                  FROM price_index_ticks
                 WHERE symbol = ?
                   AND index_price IS NOT NULL
                 ORDER BY event_time DESC
                 LIMIT 1
                """;
        List<IndexPriceResponse> rows = jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(
                rs.getString("symbol"),
                rs.getLong("sequence"),
                rs.getBigDecimal("index_price"),
                PriceStatus.valueOf(rs.getString("status")),
                rs.getInt("component_count"),
                rs.getInt("valid_component_count"),
                rs.getTimestamp("event_time").toInstant()), symbol);
        return rows.stream().findFirst();
    }

    public List<IndexPriceResponse> history(String symbol, Instant startTime, Instant endTime, int limit) {
        String sql = """
                SELECT symbol, sequence, index_price, status, component_count, valid_component_count, event_time
                  FROM price_index_ticks
                 WHERE symbol = ?
                   AND event_time >= ?
                   AND event_time < ?
                   AND index_price IS NOT NULL
                 ORDER BY event_time ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> toResponse(
                        rs.getString("symbol"),
                        rs.getLong("sequence"),
                        rs.getBigDecimal("index_price"),
                        PriceStatus.valueOf(rs.getString("status")),
                        rs.getInt("component_count"),
                        rs.getInt("valid_component_count"),
                        rs.getTimestamp("event_time").toInstant()),
                symbol, Timestamp.from(startTime), Timestamp.from(endTime), limit);
    }

    private void saveComponents(IndexPriceEvent event) {
        List<IndexComponentSnapshot> components = event.components();
        if (components == null || components.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_COMPONENT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                IndexComponentSnapshot component = components.get(i);
                ps.setString(1, event.symbol());
                ps.setLong(2, event.sequence());
                ps.setString(3, component.source());
                ps.setString(4, component.sourceSymbol());
                ps.setBigDecimal(5, component.price());
                ps.setBigDecimal(6, component.bidPrice());
                ps.setBigDecimal(7, component.askPrice());
                ps.setBigDecimal(8, component.configuredWeight());
                ps.setBigDecimal(9, component.effectiveWeight());
                ps.setString(10, component.status().name());
                ps.setString(11, component.reason());
                setTimestamp(ps, 12, component.sourceTime());
                setTimestamp(ps, 13, component.receivedAt());
                if (component.latencyMillis() == null) {
                    ps.setNull(14, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(14, component.latencyMillis());
                }
            }

            @Override
            public int getBatchSize() {
                return components.size();
            }
        });
    }

    private IndexPriceResponse toResponse(String symbol, long sequence, java.math.BigDecimal indexPrice,
                                          PriceStatus status, int componentCount, int validComponentCount,
                                          Instant eventTime) {
        return new IndexPriceResponse(symbol, indexPrice, sequence, status, componentCount,
                validComponentCount, eventTime, components(symbol, sequence));
    }

    private List<IndexComponentSnapshot> components(String symbol, long sequence) {
        String sql = """
                SELECT source, source_symbol, price, bid_price, ask_price,
                       configured_weight, effective_weight, status, reason, source_time, received_at, latency_millis
                  FROM price_index_components
                 WHERE symbol = ?
                   AND sequence = ?
                 ORDER BY source ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new IndexComponentSnapshot(
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
                nullableLong(rs, "latency_millis")), symbol, sequence);
    }

    private void setTimestamp(PreparedStatement ps, int index, Instant instant) throws java.sql.SQLException {
        if (instant == null) {
            ps.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setTimestamp(index, Timestamp.from(instant));
        }
    }

    private Instant timestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
