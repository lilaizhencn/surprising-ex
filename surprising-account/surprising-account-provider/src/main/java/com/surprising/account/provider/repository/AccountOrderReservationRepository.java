package com.surprising.account.provider.repository;

import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.provider.service.AccountMarginReleaseMath;
import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountOrderReservationRepository {

    private static final String USDT_PERPETUAL = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountOrderReservationRepository(JdbcTemplate jdbcTemplate,
                                             AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public boolean reserve(ProductLine productLine,
                           long userId,
                           OrderReserveAccountCommand command,
                           Instant now) {
        requireOrderIdentity(productLine, userId, command.orderId(), command.symbol(), command.side().name(),
                "PENDING_RESERVE");
        if (command.reservationKind() == OrderReservationKind.SPOT_ASSET) {
            return reserveSpot(userId, command, now);
        }
        return reserveDerivative(userId, command, now);
    }

    public void release(ProductLine productLine,
                        long userId,
                        long orderId,
                        boolean releaseAll,
                        String reason,
                        Instant now) {
        requireOrderIdentity(productLine, userId, orderId, null, null, null);
        Reservation derivative = lockDerivative(orderId);
        if (derivative != null) {
            releaseDerivative(orderId, derivative, releaseAll, reason, now);
            return;
        }
        SpotReservation spot = lockSpot(orderId);
        if (spot != null) {
            releaseSpot(orderId, spot, releaseAll, reason, now);
            return;
        }
        if (hasAnyReservation(orderId)) {
            return;
        }
        Boolean reduceOnly = jdbcTemplate.query("""
                SELECT reduce_only
                  FROM trading_orders
                 WHERE order_id = ?
                """, (rs, rowNum) -> rs.getBoolean("reduce_only"), orderId).stream().findFirst().orElse(null);
        if (reduceOnly == null) {
            throw new IllegalStateException("order not found for account reservation release " + orderId);
        }
        if (!reduceOnly) {
            throw new IllegalStateException("missing account reservation for non-reduce-only order " + orderId);
        }
    }

    private boolean reserveDerivative(long userId, OrderReserveAccountCommand command, Instant now) {
        String accountType = command.accountType().name();
        if (usesProductBalance(accountType)) {
            jdbcTemplate.update("""
                    INSERT INTO account_product_balances (
                        account_type, user_id, asset, available_units, locked_units, updated_at
                    ) VALUES (?, ?, ?, 0, 0, ?)
                    ON CONFLICT (account_type, user_id, asset) DO NOTHING
                    """, accountType, userId, command.asset(), Timestamp.from(now));
            int balanceRows = jdbcTemplate.update("""
                    UPDATE account_product_balances
                       SET available_units = available_units - ?,
                           locked_units = locked_units + ?,
                           updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND available_units >= ?
                    """, command.reservedUnits(), command.reservedUnits(), Timestamp.from(now),
                    accountType, userId, command.asset(), command.reservedUnits());
            if (balanceRows == 0) {
                return false;
            }
        } else {
            jdbcTemplate.update("""
                    INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                    VALUES (?, ?, 0, 0, ?)
                    ON CONFLICT (user_id, asset) DO NOTHING
                    """, userId, command.asset(), Timestamp.from(now));
            int balanceRows = jdbcTemplate.update("""
                    UPDATE account_balances
                       SET available_units = available_units - ?,
                           locked_units = locked_units + ?,
                           updated_at = ?
                     WHERE user_id = ? AND asset = ?
                       AND available_units >= ?
                    """, command.reservedUnits(), command.reservedUnits(), Timestamp.from(now),
                    userId, command.asset(), command.reservedUnits());
            if (balanceRows == 0) {
                return false;
            }
        }
        int rows = jdbcTemplate.update("""
                INSERT INTO account_margin_reservations (
                    reservation_id, account_type, user_id, asset, order_id, symbol,
                    margin_mode, position_side, reserved_units, released_units, status, reason,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'ORDER_INITIAL_MARGIN', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, sequenceRepository.nextSequence("margin-reservation"), accountType, userId, command.asset(),
                command.orderId(), command.symbol(), command.marginMode().name(), command.positionSide().name(),
                command.reservedUnits(), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account margin reservation insert");
        return true;
    }

    private boolean reserveSpot(long userId, OrderReserveAccountCommand command, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES ('SPOT', ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, userId, command.asset(), Timestamp.from(now));
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT' AND user_id = ? AND asset = ?
                   AND available_units >= ?
                """, command.reservedUnits(), command.reservedUnits(), Timestamp.from(now),
                userId, command.asset(), command.reservedUnits());
        if (balanceRows == 0) {
            return false;
        }
        int rows = jdbcTemplate.update("""
                INSERT INTO account_spot_order_reservations (
                    reservation_id, order_id, user_id, symbol, side, asset, reserved_units,
                    settled_units, released_units, status, reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', 'SPOT_ORDER_LOCK', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, sequenceRepository.nextSequence("spot-reservation"), command.orderId(), userId,
                command.symbol(), command.side().name(), command.asset(), command.reservedUnits(),
                Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account spot reservation insert");
        return true;
    }

    private void releaseDerivative(long orderId,
                                   Reservation reservation,
                                   boolean releaseAll,
                                   String reason,
                                   Instant now) {
        long amountUnits;
        if (releaseAll) {
            amountUnits = Math.subtractExact(reservation.reservedUnits(),
                    Math.addExact(reservation.releasedUnits(), reservation.consumedUnits()));
        } else {
            long[] order = orderQuantity(orderId);
            amountUnits = AccountMarginReleaseMath.releaseForRemaining(reservation.reservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), order[0], order[1]);
        }
        if (amountUnits <= 0) {
            return;
        }
        releaseBalance(reservation.accountType(), reservation.userId(), reservation.asset(), amountUnits, now);
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
                   AND released_units + position_margin_units + ? <= reserved_units
                """, amountUnits, amountUnits, amountUnits, reason, Timestamp.from(now), orderId, amountUnits);
        requireSingleRow(rows, "account margin reservation release");
    }

    private void releaseSpot(long orderId,
                             SpotReservation reservation,
                             boolean releaseAll,
                             String reason,
                             Instant now) {
        long amountUnits;
        if (releaseAll) {
            amountUnits = Math.subtractExact(reservation.reservedUnits(),
                    Math.addExact(reservation.releasedUnits(), reservation.settledUnits()));
        } else {
            long[] order = orderQuantity(orderId);
            amountUnits = AccountMarginReleaseMath.releaseForRemaining(reservation.reservedUnits(),
                    reservation.releasedUnits(), reservation.settledUnits(), order[0], order[1]);
        }
        if (amountUnits <= 0) {
            return;
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE account_type = 'SPOT' AND user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), reservation.userId(), reservation.asset(),
                amountUnits);
        requireSingleRow(balanceRows, "account spot balance release");
        int rows = jdbcTemplate.update("""
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
        requireSingleRow(rows, "account spot reservation release");
    }

    private Reservation lockDerivative(long orderId) {
        return jdbcTemplate.query("""
                SELECT account_type, user_id, asset, reserved_units, released_units, position_margin_units
                  FROM account_margin_reservations
                 WHERE order_id = ? AND status NOT IN ('RELEASED', 'CONSUMED')
                 FOR UPDATE
                """, (rs, rowNum) -> new Reservation(
                normalizeMarginAccountType(rs.getString("account_type")), rs.getLong("user_id"),
                rs.getString("asset"), rs.getLong("reserved_units"), rs.getLong("released_units"),
                rs.getLong("position_margin_units")), orderId).stream().findFirst().orElse(null);
    }

    private SpotReservation lockSpot(long orderId) {
        return jdbcTemplate.query("""
                SELECT user_id, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ? AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> new SpotReservation(
                rs.getLong("user_id"), rs.getString("asset"), rs.getLong("reserved_units"),
                rs.getLong("settled_units"), rs.getLong("released_units")),
                orderId).stream().findFirst().orElse(null);
    }

    private long[] orderQuantity(long orderId) {
        return jdbcTemplate.query("""
                SELECT quantity_steps, remaining_quantity_steps
                  FROM trading_orders
                 WHERE order_id = ?
                """, (rs, rowNum) -> new long[] {
                rs.getLong("quantity_steps"), rs.getLong("remaining_quantity_steps")
        }, orderId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("order not found for reservation release " + orderId));
    }

    private boolean hasAnyReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT 1
                  FROM account_margin_reservations
                 WHERE order_id = ?
                UNION ALL
                SELECT 1
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                 LIMIT 1
                """, (rs, rowNum) -> 1, orderId, orderId).stream().findFirst().isPresent();
    }

    private void requireOrderIdentity(ProductLine productLine,
                                      long userId,
                                      long orderId,
                                      String symbol,
                                      String side,
                                      String requiredStatus) {
        boolean matches = jdbcTemplate.query("""
                SELECT 1
                  FROM trading_orders
                 WHERE order_id = ?
                   AND product_line = ?
                   AND user_id = ?
                   AND (CAST(? AS text) IS NULL OR symbol = ?)
                   AND (CAST(? AS text) IS NULL OR side = ?)
                   AND (CAST(? AS text) IS NULL OR status = ?)
                """, (rs, rowNum) -> true, orderId, productLine.name(), userId,
                symbol, symbol, side, side, requiredStatus, requiredStatus)
                .stream().findFirst().orElse(false);
        if (!matches) {
            throw new IllegalStateException("account reservation order identity mismatch " + orderId);
        }
    }

    private void releaseBalance(String accountType,
                                long userId,
                                String asset,
                                long amountUnits,
                                Instant now) {
        int rows;
        if (usesProductBalance(accountType)) {
            rows = jdbcTemplate.update("""
                    UPDATE account_product_balances
                       SET locked_units = locked_units - ?,
                           available_units = available_units + ?,
                           updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND locked_units >= ?
                    """, amountUnits, amountUnits, Timestamp.from(now), accountType, userId, asset, amountUnits);
        } else {
            rows = jdbcTemplate.update("""
                    UPDATE account_balances
                       SET locked_units = locked_units - ?,
                           available_units = available_units + ?,
                           updated_at = ?
                     WHERE user_id = ? AND asset = ?
                       AND locked_units >= ?
                    """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        }
        requireSingleRow(rows, "account locked balance release");
    }

    private String normalizeMarginAccountType(String accountType) {
        String normalized = accountType == null || accountType.isBlank() ? USDT_PERPETUAL
                : accountType.trim().toUpperCase();
        ProductLine productLine = ProductLine.requireAccountTypeCode(normalized);
        if (!productLine.isMarginProduct()) {
            throw new IllegalStateException("invalid margin reservation account type " + accountType);
        }
        return normalized;
    }

    private boolean usesProductBalance(String accountType) {
        return !USDT_PERPETUAL.equals(accountType);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private record Reservation(
            String accountType,
            long userId,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long consumedUnits) {
    }

    private record SpotReservation(
            long userId,
            String asset,
            long reservedUnits,
            long settledUnits,
            long releasedUnits) {
    }
}
