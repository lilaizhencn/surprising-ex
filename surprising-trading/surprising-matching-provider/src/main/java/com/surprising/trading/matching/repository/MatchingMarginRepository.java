package com.surprising.trading.matching.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.service.MarginReleaseMath;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingMarginRepository {

    private static final String USDT_PERPETUAL = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;

    public MatchingMarginRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void releaseAll(long orderId, String reason, Instant now) {
        var rows = lockReservation(orderId);
        if (rows == null) {
            if (releaseAllSpot(orderId, reason, now)) {
                return;
            }
            if (hasAnyReservation(orderId)) {
                return;
            }
            requireReservationUnlessReduceOnly(orderId);
            return;
        }
        release(orderId, rows.accountType(), rows.userId(), rows.asset(),
                rows.reservedUnits() - rows.releasedUnits() - rows.positionMarginUnits(), reason, now);
    }

    public void releaseUnused(long orderId, String reason, Instant now) {
        var reservation = lockReservation(orderId);
        if (reservation == null) {
            if (releaseUnusedSpot(orderId, reason, now)) {
                return;
            }
            if (hasAnyReservation(orderId)) {
                return;
            }
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
        release(orderId, reservation.accountType(), reservation.userId(), reservation.asset(), releaseUnits, reason, now);
    }

    private boolean releaseAllSpot(long orderId, String reason, Instant now) {
        SpotReservation reservation = lockSpotReservation(orderId);
        if (reservation == null) {
            return false;
        }
        long amountUnits = Math.subtractExact(reservation.reservedUnits(),
                Math.addExact(reservation.settledUnits(), reservation.releasedUnits()));
        releaseSpot(orderId, reservation.userId(), reservation.asset(), amountUnits, reason, now);
        return true;
    }

    private boolean releaseUnusedSpot(long orderId, String reason, Instant now) {
        SpotReservation reservation = lockSpotReservation(orderId);
        if (reservation == null) {
            return false;
        }
        var order = jdbcTemplate.query("""
                SELECT quantity_steps, remaining_quantity_steps
                  FROM trading_orders
                 WHERE order_id = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new long[] {rs.getLong("quantity_steps"), rs.getLong("remaining_quantity_steps")},
                orderId).stream().findFirst().orElse(null);
        if (order == null) {
            throw new IllegalStateException("order not found for matching spot release " + orderId);
        }
        long releaseUnits = MarginReleaseMath.releaseForRemaining(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.settledUnits(), order[0], order[1]);
        releaseSpot(orderId, reservation.userId(), reservation.asset(), releaseUnits, reason, now);
        return true;
    }

    private Reservation lockReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT account_type, user_id, asset, reserved_units, released_units, position_margin_units
                  FROM account_margin_reservations
                 WHERE order_id = ?
                   AND status NOT IN ('RELEASED', 'CONSUMED')
                 FOR UPDATE
                """, (rs, rowNum) -> new Reservation(
                normalizeMarginAccountType(rs.getString("account_type")),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("released_units"),
                rs.getLong("position_margin_units")), orderId).stream().findFirst().orElse(null);
    }

    private SpotReservation lockSpotReservation(long orderId) {
        var rows = jdbcTemplate.query("""
                SELECT user_id, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                   AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> new SpotReservation(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("settled_units"),
                rs.getLong("released_units")), orderId);
        return rows == null ? null : rows.stream().findFirst().orElse(null);
    }

    private boolean hasAnyReservation(long orderId) {
        return hasAnyMarginReservation(orderId) || hasAnySpotReservation(orderId);
    }

    private boolean hasAnyMarginReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_margin_reservations
                 WHERE order_id = ?
                 LIMIT 1
                """, (rs, rowNum) -> 1, orderId).stream().findFirst().isPresent();
    }

    private boolean hasAnySpotReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                 LIMIT 1
                """, (rs, rowNum) -> 1, orderId).stream().findFirst().isPresent();
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

    private void release(long orderId,
                         String accountType,
                         long userId,
                         String asset,
                         long amountUnits,
                         String reason,
                         Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        releaseBalanceLock(normalizeMarginAccountType(accountType), userId, asset, amountUnits, now);
        int rows = jdbcTemplate.update("""
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

    private void releaseSpot(long orderId, long userId, String asset, long amountUnits, String reason, Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        int rows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT'
                   AND user_id = ?
                   AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked spot balance for matching release");
        }
        rows = jdbcTemplate.update("""
                UPDATE account_spot_order_reservations
                   SET released_units = released_units + ?,
                       status = CASE
                           WHEN released_units + ? >= reserved_units AND settled_units = 0 THEN 'RELEASED'
                           WHEN released_units + ? + settled_units >= reserved_units THEN 'SETTLED'
                           WHEN settled_units > 0 THEN 'PARTIALLY_SETTLED'
                           ELSE 'PARTIALLY_RELEASED'
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND released_units + settled_units + ? <= reserved_units
                """, amountUnits, amountUnits, amountUnits, reason, Timestamp.from(now), orderId, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("failed to update matching spot reservation");
        }
    }

    private void releaseBalanceLock(String accountType, long userId, String asset, long amountUnits, Instant now) {
        if (usesProductMarginBalance(accountType)) {
            int rows = jdbcTemplate.update("""
                    UPDATE account_product_balances
                       SET locked_units = locked_units - ?,
                           available_units = available_units + ?,
                           updated_at = ?
                     WHERE account_type = ?
                       AND user_id = ?
                       AND asset = ?
                       AND locked_units >= ?
                    """, amountUnits, amountUnits, Timestamp.from(now), accountType, userId, asset, amountUnits);
            if (rows != 1) {
                throw new IllegalStateException("insufficient locked product balance for matching margin release");
            }
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
    }

    private String normalizeMarginAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return USDT_PERPETUAL;
        }
        String normalized = accountType.trim().toUpperCase();
        ProductLine productLine = ProductLine.requireAccountTypeCode(normalized);
        if (!productLine.isMarginProduct()) {
            throw new IllegalStateException("invalid margin reservation account type " + accountType);
        }
        return normalized;
    }

    private boolean usesProductMarginBalance(String normalizedAccountType) {
        return !USDT_PERPETUAL.equals(normalizedAccountType);
    }

    private record Reservation(String accountType,
                               long userId,
                               String asset,
                               long reservedUnits,
                               long releasedUnits,
                               long positionMarginUnits) {
    }

    private record SpotReservation(long userId,
                                   String asset,
                                   long reservedUnits,
                                   long settledUnits,
                                   long releasedUnits) {
    }
}
