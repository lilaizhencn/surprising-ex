package com.surprising.price.mark.repository;

import com.surprising.price.api.model.MarkPriceAuditEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import com.surprising.price.mark.model.MarkPriceEncoding;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarkPriceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MarkPriceProperties properties;

    MarkPriceRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new MarkPriceProperties());
    }

    @Autowired
    public MarkPriceRepository(JdbcTemplate jdbcTemplate, MarkPriceProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new MarkPriceProperties() : properties;
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

    public MarkPriceEncoding encoding(String symbol) {
        List<Object> args = new ArrayList<>(List.of(symbol));
        String productCondition = productCondition(args, "i");
        return jdbcTemplate.query("""
                SELECT i.version, qs.scale_units, i.price_tick_units
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN account_asset_scales qs
                    ON qs.asset = i.quote_asset
                 WHERE i.symbol = ?
                %s
                """.formatted(productCondition), (rs, rowNum) -> new MarkPriceEncoding(
                rs.getLong("version"), rs.getLong("scale_units"), rs.getLong("price_tick_units")), args.toArray())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("mark price encoding not found for " + symbol));
    }

    private static final String INSERT_AUDIT_SQL = """
                INSERT INTO price_mark_ticks (
                    product_line, symbol, instrument_version, sequence, mark_price, mark_price_units,
                    mark_price_ticks, index_price, price1, price2, last_trade_price,
                    best_bid_price, best_ask_price, funding_rate, next_funding_time, time_until_funding_seconds,
                    basis_average, basis_window_seconds, clamp_low, clamp_high, status, event_time, published_at,
                    calculation_inputs
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """;

    public void saveBatch(List<MarkPriceAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(INSERT_AUDIT_SQL, new BatchPreparedStatementSetter() {
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

    public int deleteAuditBefore(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                DELETE FROM price_mark_ticks
                 WHERE ctid IN (
                    SELECT ctid
                      FROM price_mark_ticks
                     WHERE event_time < ?
                     ORDER BY event_time
                     LIMIT ?
                 )
                """, Timestamp.from(cutoff), batchSize);
    }

    public Optional<MarkPriceResponse> latest(String symbol) {
        String sql = """
                SELECT *
                  FROM price_mark_ticks
                 WHERE symbol = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """;
        List<MarkPriceResponse> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new MarkPriceResponse(
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
                rs.getTimestamp("event_time").toInstant()), symbol);
        return rows.stream().findFirst();
    }

    public List<MarkPriceResponse> history(String symbol, Instant startTime, Instant endTime, int limit) {
        String sql = """
                SELECT *
                  FROM price_mark_ticks
                 WHERE symbol = ?
                   AND event_time >= ?
                   AND event_time < ?
                 ORDER BY event_time ASC
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new MarkPriceResponse(
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

    private String productCondition(List<Object> args, String alias) {
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return "";
        }
        args.add(properties.getKafka().getProductLine().contractTypeCode());
        return "   AND " + alias + ".contract_type = ?";
    }
}
