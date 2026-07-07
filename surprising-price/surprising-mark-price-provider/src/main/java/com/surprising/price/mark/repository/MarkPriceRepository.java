package com.surprising.price.mark.repository;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPriceResponse;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    public void save(MarkPriceEvent event) {
        List<Object> args = new ArrayList<>(List.of(event.symbol(), event.sequence(), event.markPrice(),
                event.markPrice(), event.indexPrice(), event.price1(), event.price2(), event.lastTradePrice(),
                event.bestBidPrice(), event.bestAskPrice(), event.fundingRate(),
                Timestamp.from(event.nextFundingTime()), event.timeUntilFundingSeconds(), event.basisAverage(),
                event.basisWindowSeconds(), event.clampLow(), event.clampHigh(), event.status().name(),
                Timestamp.from(event.eventTime()), event.symbol()));
        String productCondition = productCondition(args, "i");
        int rows = jdbcTemplate.update("""
                INSERT INTO price_mark_ticks (
                    symbol, sequence, mark_price, mark_price_units, index_price, price1, price2, last_trade_price,
                    best_bid_price, best_ask_price, funding_rate, next_funding_time, time_until_funding_seconds,
                    basis_average, basis_window_seconds, clamp_low, clamp_high, status, event_time
                )
                SELECT ?, ?, ?,
                       CAST(round(? * qs.scale_units) AS BIGINT),
                       ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                  FROM instruments i
                  JOIN instrument_current_versions c
                    ON c.symbol = i.symbol AND c.version = i.version
                  JOIN account_asset_scales qs
                    ON qs.asset = i.quote_asset
                 WHERE i.symbol = ?
                %s
                ON CONFLICT (symbol, sequence) DO NOTHING
                """.formatted(productCondition), args.toArray());
        if (rows != 1) {
            throw new IllegalStateException("mark price insert failed for " + event.symbol()
                    + " sequence=" + event.sequence());
        }
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
