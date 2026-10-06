package com.surprising.instrument.provider.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads one bounded 24-hour market snapshot for the public instrument list. */
@Repository
public class MarketSummaryRepository {
    private static final int TREND_POINTS = 32;

    private final NamedParameterJdbcTemplate jdbc;

    public MarketSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Integer, Summary> summaries(List<Integer> instrumentIds, Instant now) {
        if (instrumentIds.isEmpty()) return Map.of();
        var arguments = new MapSqlParameterSource()
                .addValue("ids", instrumentIds.stream().map(String::valueOf).toList())
                .addValue("since", Timestamp.from(now.minusSeconds(86_400)))
                .addValue("until", Timestamp.from(now));
        var rows = jdbc.query("""
                WITH eligible AS (
                    SELECT i.instrument_id::text AS instrument_id
                      FROM instruments i WHERE i.instrument_id::text IN (:ids)
                     GROUP BY i.instrument_id HAVING count(DISTINCT i.product_line) = 1
                ), samples AS (
                    SELECT e.instrument_id, p.close_price AS open_price, p.close_price AS high_price,
                           p.close_price AS low_price, p.close_price, 0::numeric AS base_volume,
                           0::numeric AS quote_volume, :since::timestamptz AS sample_time, 0 AS sample_order
                      FROM eligible e CROSS JOIN LATERAL (
                          SELECT c.close_price FROM candlestick_candles c
                           WHERE c.instrument_id = e.instrument_id AND c.period = '1m'
                             AND c.status = 'CLOSED' AND c.close_time <= :since
                           ORDER BY c.open_time DESC LIMIT 1
                      ) p
                    UNION ALL
                    SELECT c.instrument_id, c.open_price, c.high_price, c.low_price,
                           c.close_price, c.base_volume, c.quote_volume, c.open_time, 1
                      FROM candlestick_candles c JOIN eligible e USING (instrument_id)
                     WHERE c.period = '1m' AND c.status = 'CLOSED'
                       AND c.open_time >= :since AND c.open_time < :until
                )
                SELECT instrument_id, open_price, high_price, low_price, close_price, base_volume, quote_volume
                  FROM samples ORDER BY instrument_id, sample_time, sample_order
                """, arguments, (rs, index) -> new Sample(
                Integer.parseInt(rs.getString("instrument_id")),
                rs.getBigDecimal("open_price"), rs.getBigDecimal("high_price"),
                rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
                rs.getBigDecimal("base_volume"), rs.getBigDecimal("quote_volume")));
        Map<Integer, List<Sample>> grouped = new HashMap<>();
        for (Sample row : rows) grouped.computeIfAbsent(row.instrumentId(), ignored -> new ArrayList<>()).add(row);
        Map<Integer, Summary> result = new HashMap<>();
        grouped.forEach((instrumentId, samples) -> result.put(instrumentId, summarize(samples)));
        return result;
    }

    static Summary summarize(List<Sample> rows) {
        BigDecimal open = rows.getFirst().open();
        BigDecimal close = rows.getLast().close();
        BigDecimal high = open;
        BigDecimal low = open;
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal quoteVolume = BigDecimal.ZERO;
        for (Sample row : rows) {
            high = high.max(row.high());
            low = low.min(row.low());
            volume = volume.add(row.volume());
            quoteVolume = quoteVolume.add(row.quoteVolume());
        }
        List<BigDecimal> trend = new ArrayList<>(Math.min(rows.size(), TREND_POINTS));
        int count = Math.min(rows.size(), TREND_POINTS);
        for (int i = 0; i < count; i++) {
            int index = count == 1 ? 0 : i * (rows.size() - 1) / (count - 1);
            trend.add(rows.get(index).close());
        }
        // With no executions in the window, both endpoints remain the last real close.
        if (trend.size() == 1) trend.add(trend.getFirst());
        BigDecimal change = close.subtract(open).multiply(BigDecimal.valueOf(100))
                .divide(open, 6, RoundingMode.HALF_UP);
        return new Summary(close, change, high, low, volume, quoteVolume, List.copyOf(trend));
    }

    record Sample(int instrumentId, BigDecimal open, BigDecimal high, BigDecimal low,
                  BigDecimal close, BigDecimal volume, BigDecimal quoteVolume) {}

    public record Summary(BigDecimal lastPrice, BigDecimal change24h, BigDecimal high24h,
                          BigDecimal low24h, BigDecimal volume24h, BigDecimal quoteVolume24h,
                          List<BigDecimal> trend) {}
}
