package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CancelAllAfterRepository {

    public static final String ALL_SYMBOLS_SCOPE = "*";

    private final JdbcTemplate jdbcTemplate;

    public CancelAllAfterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public CancelAllAfterTimer upsert(long userId, String symbolScope, long countdownMs,
                                      Instant triggerAt, String status, Instant now, String traceId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO trading_cancel_all_after (
                    user_id, symbol_scope, countdown_ms, status, trigger_at,
                    last_heartbeat_at, triggered_at, trace_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                ON CONFLICT (user_id, symbol_scope) DO UPDATE SET
                    countdown_ms = EXCLUDED.countdown_ms,
                    status = EXCLUDED.status,
                    trigger_at = EXCLUDED.trigger_at,
                    last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                    triggered_at = NULL,
                    canceled_order_count = 0,
                    canceled_trigger_order_count = 0,
                    trace_id = EXCLUDED.trace_id,
                    last_error = NULL,
                    updated_at = EXCLUDED.updated_at
                RETURNING user_id, symbol_scope, countdown_ms, status, trigger_at, updated_at,
                          canceled_order_count, canceled_trigger_order_count
                """, (rs, rowNum) -> map(rs), userId, symbolScope, countdownMs, status,
                timestampOrNull(triggerAt), Timestamp.from(now), emptyToNull(traceId),
                Timestamp.from(now), Timestamp.from(now));
    }

    public List<CancelAllAfterTimer> claimDueTimers(Instant now, int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT user_id, symbol_scope
                      FROM trading_cancel_all_after
                     WHERE status = 'ACTIVE'
                       AND trigger_at <= ?
                     ORDER BY trigger_at ASC, user_id ASC, symbol_scope ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE trading_cancel_all_after timer
                   SET status = 'TRIGGERING',
                       updated_at = ?
                  FROM due
                 WHERE timer.user_id = due.user_id
                   AND timer.symbol_scope = due.symbol_scope
                RETURNING timer.user_id, timer.symbol_scope, timer.countdown_ms, timer.status,
                          timer.trigger_at, timer.updated_at,
                          timer.canceled_order_count, timer.canceled_trigger_order_count
                """, (rs, rowNum) -> map(rs), Timestamp.from(now), limit, Timestamp.from(now));
    }

    public void markTriggered(long userId, String symbolScope, int canceledOrders,
                              int canceledTriggerOrders, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_cancel_all_after
                   SET status = 'TRIGGERED',
                       triggered_at = ?,
                       canceled_order_count = ?,
                       canceled_trigger_order_count = ?,
                       last_error = NULL,
                       updated_at = ?
                 WHERE user_id = ?
                   AND symbol_scope = ?
                   AND status = 'TRIGGERING'
                """, Timestamp.from(now), canceledOrders, canceledTriggerOrders,
                Timestamp.from(now), userId, symbolScope);
    }

    public void releaseForRetry(long userId, String symbolScope, String error, Instant now) {
        jdbcTemplate.update("""
                UPDATE trading_cancel_all_after
                   SET status = 'ACTIVE',
                       last_error = ?,
                       updated_at = ?
                 WHERE user_id = ?
                   AND symbol_scope = ?
                   AND status = 'TRIGGERING'
                """, truncate(error, 512), Timestamp.from(now), userId, symbolScope);
    }

    private static CancelAllAfterTimer map(ResultSet rs) throws SQLException {
        return new CancelAllAfterTimer(
                rs.getLong("user_id"),
                rs.getString("symbol_scope"),
                rs.getLong("countdown_ms"),
                rs.getString("status"),
                toInstant(rs.getTimestamp("trigger_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getInt("canceled_order_count"),
                rs.getInt("canceled_trigger_order_count"));
    }

    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
