package com.surprising.account.provider.repository;

import com.surprising.trading.api.model.OrderSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SpotOrderReservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public SpotOrderReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(long reservationId,
                      long orderId,
                      long userId,
                      String symbol,
                      OrderSide side,
                      String asset,
                      long reservedUnits,
                      Instant now) {
        return jdbcTemplate.update("""
                INSERT INTO account_spot_order_reservations (
                    reservation_id, order_id, user_id, symbol, side, asset, reserved_units,
                    settled_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', 'SPOT_ORDER_LOCK', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, reservationId, orderId, userId, symbol, side.name(), asset, reservedUnits,
                Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<SpotReservationRow> lock(long orderId) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, side, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                   AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> toRow(rs), orderId)
                .stream().findFirst();
    }

    public Optional<SpotReservationRow> lock(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, side, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                   AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> toRow(rs), orderId, userId, symbol)
                .stream().findFirst();
    }

    public int release(long orderId, long amountUnits, String reason, Instant now) {
        return jdbcTemplate.update("""
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
    }

    public int settle(long orderId,
                      long settledUnits,
                      long releasedUnits,
                      String reason,
                      Instant now) {
        return jdbcTemplate.update("""
                UPDATE account_spot_order_reservations
                   SET settled_units = settled_units + ?,
                       released_units = released_units + ?,
                       status = CASE
                           WHEN settled_units + released_units + ? + ? >= reserved_units THEN 'SETTLED'
                           WHEN settled_units + ? > 0 THEN 'PARTIALLY_SETTLED'
                           WHEN released_units + ? > 0 THEN 'PARTIALLY_RELEASED'
                           ELSE status
                       END,
                       reason = ?,
                       updated_at = ?
                 WHERE order_id = ?
                   AND settled_units + released_units + ? + ? <= reserved_units
                """, settledUnits, releasedUnits, settledUnits, releasedUnits, settledUnits, releasedUnits,
                reason, Timestamp.from(now), orderId, settledUnits, releasedUnits);
    }

    private static SpotReservationRow toRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new SpotReservationRow(
                resultSet.getLong("user_id"),
                resultSet.getString("symbol"),
                OrderSide.valueOf(resultSet.getString("side")),
                resultSet.getString("asset"),
                resultSet.getLong("reserved_units"),
                resultSet.getLong("settled_units"),
                resultSet.getLong("released_units"));
    }

    public record SpotReservationRow(
            long userId,
            String symbol,
            OrderSide side,
            String asset,
            long reservedUnits,
            long settledUnits,
            long releasedUnits) {
    }
}
