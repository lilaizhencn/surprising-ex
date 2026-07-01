package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.OrderFeeSnapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderFeeRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderFeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OrderFeeSnapshot> snapshot(long userId, String symbol, long instrumentVersion, Instant now) {
        return jdbcTemplate.query("""
                WITH instrument_fee AS (
                    SELECT maker_fee_rate_ppm, taker_fee_rate_ppm
                      FROM instruments
                     WHERE symbol = ?
                       AND version = ?
                ),
                active_user_fee AS (
                    SELECT maker_fee_rate_ppm,
                           taker_fee_rate_ppm,
                           CASE WHEN symbol = ? THEN 0 ELSE 1 END AS priority,
                           CASE WHEN symbol = ? THEN 'USER_SYMBOL' ELSE 'USER_GLOBAL' END AS source,
                           effective_time,
                           fee_schedule_id
                      FROM trading_fee_schedules
                     WHERE user_id = ?
                       AND status = 'ACTIVE'
                       AND (symbol = ? OR symbol IS NULL)
                       AND effective_time <= ?
                       AND (expire_time IS NULL OR expire_time > ?)
                     ORDER BY priority ASC, effective_time DESC, fee_schedule_id DESC
                     LIMIT 1
                )
                SELECT COALESCE(u.maker_fee_rate_ppm, i.maker_fee_rate_ppm) AS maker_fee_rate_ppm,
                       COALESCE(u.taker_fee_rate_ppm, i.taker_fee_rate_ppm) AS taker_fee_rate_ppm,
                       COALESCE(u.source, 'INSTRUMENT') AS source
                  FROM instrument_fee i
             LEFT JOIN active_user_fee u ON TRUE
                """, (rs, rowNum) -> new OrderFeeSnapshot(
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm"),
                rs.getString("source")), symbol, instrumentVersion, symbol, symbol, userId, symbol,
                Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
    }
}
