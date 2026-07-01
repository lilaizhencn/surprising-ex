package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.MarkPriceLookup;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderMarkPriceRepository implements MarkPriceLookup {

    private final JdbcTemplate jdbcTemplate;

    public OrderMarkPriceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public OptionalLong latestMarkPriceTicks(String symbol, long instrumentVersion, long maxAgeMs) {
        String sql = """
                SELECT ((m.mark_price_units + i.price_tick_units / 2) / i.price_tick_units) AS mark_ticks
                  FROM instruments i
                  JOIN LATERAL (
                      SELECT mark_price_units, event_time
                        FROM price_mark_ticks
                       WHERE symbol = i.symbol
                         AND event_time >= now() - (? * INTERVAL '1 millisecond')
                       ORDER BY event_time DESC
                       LIMIT 1
                  ) m ON TRUE
                 WHERE i.symbol = ?
                   AND i.version = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("mark_ticks"),
                Math.max(1L, maxAgeMs), symbol, instrumentVersion)
                .stream()
                .mapToLong(Long::longValue)
                .filter(value -> value > 0)
                .findFirst();
    }
}
