package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.service.MarginReleaseMath;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingMarginRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingMarginRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void releaseAll(long orderId, String reason, Instant now) {
        var rows = lockReservation(orderId);
        if (rows == null) {
            requireReservationUnlessReduceOnly(orderId);
            return;
        }
        release(orderId, rows.userId(), rows.asset(),
                rows.reservedUnits() - rows.releasedUnits() - rows.positionMarginUnits(), reason, now);
    }

    public void releaseUnused(long orderId, String reason, Instant now) {
        var reservation = lockReservation(orderId);
        if (reservation == null) {
            requireReservationUnlessReduceOnly(orderId);
            return;
        }
        var order = jdbcTemplate.query("""
                SELECT quantity_steps, remaining_quantity_steps
                  FROM trading_orders
                 WHERE order_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("quantity_steps"), rs.getLong("remaining_quantity_steps")},
                orderId).stream().findFirst().orElse(null);
        if (order == null) {
            throw new IllegalStateException("order not found for matching margin release " + orderId);
        }
        // Do not release margin already moved by account-provider into position collateral.
        long releaseUnits = MarginReleaseMath.releaseForRemaining(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), order[0], order[1]);
        release(orderId, reservation.userId(), reservation.asset(), releaseUnits, reason, now);
    }

    private Reservation lockReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT user_id, asset, reserved_units, released_units, position_margin_units
                  FROM account_margin_reservations
                 WHERE order_id = ?
                   AND status NOT IN ('RELEASED', 'CONSUMED')
                 FOR UPDATE
                """, (rs, rowNum) -> new Reservation(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("released_units"),
                rs.getLong("position_margin_units")), orderId).stream().findFirst().orElse(null);
    }

    private void requireReservationUnlessReduceOnly(long orderId) {
        Boolean reduceOnly = jdbcTemplate.query("""
                SELECT reduce_only
                  FROM trading_orders
                 WHERE order_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getBoolean("reduce_only"), orderId).stream().findFirst().orElse(null);
        if (reduceOnly == null) {
            throw new IllegalStateException("order not found for matching margin release " + orderId);
        }
        if (!reduceOnly) {
            throw new IllegalStateException("missing margin reservation for non-reduce-only order " + orderId);
        }
    }

    private void release(long orderId, long userId, String asset, long amountUnits, String reason, Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        // Fail on broken locked-balance invariants instead of minting available funds from an over-release.
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked balance for matching margin release");
        }
        rows = jdbcTemplate.update("""
                UPDATE account_margin_reservations
                   SET released_units = released_units + ?,
                       status = CASE
                           WHEN released_units + ? >= reserved_units AND position_margin_units = 0 THEN 'RELEASED'
                           WHEN released_units + ? + position_margin_units >= reserved_units THEN 'CONSUMED'
                           WHEN position_margin_units > 0 THEN 'PARTIALLY_CONSUMED'
                           ELSE 'PARTIALLY_RELEASED'
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                """, amountUnits, amountUnits, amountUnits, reason, Timestamp.from(now), orderId);
        if (rows != 1) {
            throw new IllegalStateException("failed to update matching margin reservation");
        }
    }

    private record Reservation(long userId,
                               String asset,
                               long reservedUnits,
                               long releasedUnits,
                               long positionMarginUnits) {
    }
}
