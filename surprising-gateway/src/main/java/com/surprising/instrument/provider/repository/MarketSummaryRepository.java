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
                SELECT c.instrument_id, c.open_price, c.high_price, c.low_price,
                       c.close_price, c.base_volume, c.quote_volume
                  FROM candlestick_candles c
                 WHERE c.instrument_id IN (:ids)
                   AND c.period = '1m'
                   AND c.status = 'CLOSED'
                   AND c.open_time >= :since
                   AND c.open_time < :until
                   AND NOT EXISTS (
                       SELECT 1 FROM instruments i
                        WHERE i.instrument_id = c.instrument_id::integer
                        GROUP BY i.instrument_id
                       HAVING count(DISTINCT i.product_line) > 1
                   )
                 ORDER BY c.instrument_id, c.open_time
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
