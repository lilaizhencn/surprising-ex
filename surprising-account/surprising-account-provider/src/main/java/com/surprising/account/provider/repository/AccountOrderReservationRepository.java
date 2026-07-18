package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
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
        requireAccountScope(productLine, command.accountType());
        if (command.reservationKind() == OrderReservationKind.SPOT_ASSET) {
            if (command.accountType() != AccountType.SPOT) {
                throw new IllegalStateException("spot reservation requires SPOT account");
            }
            return reserveSpot(userId, command, now);
        }
        if (command.accountType() == AccountType.SPOT) {
            throw new IllegalStateException("derivative reservation requires margin account");
        }
        return reserveDerivative(userId, command, now);
    }

    public void release(ProductLine productLine,
                        long userId,
                        long orderId,
                        boolean releaseAll,
                        long quantitySteps,
                        long remainingQuantitySteps,
                        boolean reservationExpected,
                        String reason,
                        Instant now) {
        Reservation derivative = lockDerivative(orderId);
        if (derivative != null) {
            requireReservationScope(productLine, userId, derivative.userId(), derivative.accountType(), orderId);
            releaseDerivative(orderId, derivative, releaseAll, quantitySteps, remainingQuantitySteps, reason, now);
            return;
        }
        SpotReservation spot = lockSpot(orderId);
        if (spot != null) {
            requireReservationScope(productLine, userId, spot.userId(), "SPOT", orderId);
            releaseSpot(orderId, spot, releaseAll, quantitySteps, remainingQuantitySteps, reason, now);
            return;
        }
        ReservationIdentity existing = anyReservation(orderId);
        if (existing != null) {
            requireReservationScope(productLine, userId, existing.userId(), existing.accountType(), orderId);
            return;
        }
        if (reservationExpected) {
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
                    margin_mode, position_side, order_quantity_steps, reduce_only,
                    reserved_units, released_units, status, reason,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', 'ORDER_INITIAL_MARGIN', ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.MARGIN_RESERVATION),
                accountType, userId, command.asset(),
                command.orderId(), command.symbol(), command.marginMode().name(), command.positionSide().name(),
                command.orderQuantitySteps(), command.reduceOnly(),
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
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.SPOT_RESERVATION),
                command.orderId(), userId,
                command.symbol(), command.side().name(), command.asset(), command.reservedUnits(),
                Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account spot reservation insert");
        return true;
    }

    private void releaseDerivative(long orderId,
                                   Reservation reservation,
                                   boolean releaseAll,
                                   long quantitySteps,
                                   long remainingQuantitySteps,
                                   String reason,
                                   Instant now) {
        long amountUnits;
        if (releaseAll) {
            amountUnits = Math.subtractExact(reservation.reservedUnits(),
                    Math.addExact(reservation.releasedUnits(), reservation.consumedUnits()));
        } else {
            amountUnits = AccountMarginReleaseMath.releaseForExecuted(reservation.reservedUnits(),
                    reservation.releasedUnits(), reservation.consumedUnits(), quantitySteps, remainingQuantitySteps);
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
                             long quantitySteps,
                             long remainingQuantitySteps,
                             String reason,
                             Instant now) {
        long amountUnits;
        if (releaseAll) {
            amountUnits = Math.subtractExact(reservation.reservedUnits(),
                    Math.addExact(reservation.releasedUnits(), reservation.settledUnits()));
        } else {
            amountUnits = AccountMarginReleaseMath.releaseForExecuted(reservation.reservedUnits(),
                    reservation.releasedUnits(), reservation.settledUnits(), quantitySteps, remainingQuantitySteps);
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

    private ReservationIdentity anyReservation(long orderId) {
        return jdbcTemplate.query("""
                SELECT user_id, account_type
                  FROM account_margin_reservations
                 WHERE order_id = ?
                UNION ALL
                SELECT user_id, 'SPOT' AS account_type
                  FROM account_spot_order_reservations
                 WHERE order_id = ?
                 LIMIT 1
                """, (rs, rowNum) -> new ReservationIdentity(
                rs.getLong("user_id"), rs.getString("account_type")),
                orderId, orderId).stream().findFirst().orElse(null);
    }

    private void requireAccountScope(ProductLine productLine,
                                     AccountType accountType) {
        ProductLine expected = accountType.productLine()
                .orElseThrow(() -> new IllegalStateException("order reservation requires a product account"));
        if (productLine != expected) {
            throw new IllegalStateException("order reservation account does not match product line");
        }
    }

    private void requireReservationScope(ProductLine productLine,
                                         long commandUserId,
                                         long reservationUserId,
                                         String accountType,
                                         long orderId) {
        if (reservationUserId != commandUserId) {
            throw new IllegalStateException("account reservation user mismatch " + orderId);
        }
        AccountType type;
        try {
            type = AccountType.valueOf(accountType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("invalid account reservation type " + accountType, ex);
        }
        requireAccountScope(productLine, type);
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

    private record ReservationIdentity(long userId, String accountType) {
    }
}
