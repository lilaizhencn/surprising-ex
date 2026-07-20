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

    public long release(ProductLine productLine,
                        long userId,
                        long orderId,
                        boolean releaseAll,
                        long quantitySteps,
                        long remainingQuantitySteps,
                        boolean reservationExpected,
                        AccountType reservationAccountType,
                        String reservationAsset,
                        long reservedUnits,
                        String reason,
                        Instant now) {
        if (reservationAccountType == AccountType.SPOT) {
            SpotReservation spot = lockSpot(orderId);
            if (spot == null) {
                if (reservationExpected) {
                    throw new IllegalStateException("missing spot reservation for order " + orderId);
                }
                return 0L;
            }
            requireReservationScope(productLine, userId, spot.userId(), AccountType.SPOT, orderId);
            return releaseSpot(orderId, spot, releaseAll, quantitySteps, remainingQuantitySteps, reason, now);
        }
        if (reservationAccountType == null || reservationAsset == null || reservationAsset.isBlank()
                || reservedUnits <= 0L) {
            if (reservationExpected) {
                throw new IllegalStateException("missing derivative reservation snapshot for order " + orderId);
            }
            return 0L;
        }
        requireReservationScope(productLine, userId, userId, reservationAccountType, orderId);
        MarginUsage usage = marginUsage(orderId);
        long unavailable = Math.addExact(usage.consumedUnits(), usage.releasedUnits());
        if (unavailable > reservedUnits) {
            throw new IllegalStateException("order margin usage exceeds reservation for order " + orderId);
        }
        long amountUnits = releaseAll
                ? Math.subtractExact(reservedUnits, unavailable)
                : AccountMarginReleaseMath.releaseForExecuted(reservedUnits, usage.releasedUnits(),
                usage.consumedUnits(), quantitySteps, remainingQuantitySteps);
        if (amountUnits <= 0L) {
            return 0L;
        }
        releaseBalance(reservationAccountType.name(), userId, reservationAsset, amountUnits, now);
        return amountUnits;
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
            return jdbcTemplate.update("""
                    UPDATE account_product_balances
                       SET available_units = available_units - ?,
                           locked_units = locked_units + ?,
                           updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND available_units >= ?
                    """, command.reservedUnits(), command.reservedUnits(), Timestamp.from(now),
                    accountType, userId, command.asset(), command.reservedUnits()) == 1;
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, command.asset(), Timestamp.from(now));
        return jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = available_units - ?,
                       locked_units = locked_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND available_units >= ?
                """, command.reservedUnits(), command.reservedUnits(), Timestamp.from(now),
                userId, command.asset(), command.reservedUnits()) == 1;
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
                command.orderId(), userId, command.symbol(), command.side().name(), command.asset(),
                command.reservedUnits(), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "account spot reservation insert");
        return true;
    }

    private long releaseSpot(long orderId,
                             SpotReservation reservation,
                             boolean releaseAll,
                             long quantitySteps,
                             long remainingQuantitySteps,
                             String reason,
                             Instant now) {
        long amountUnits = releaseAll
                ? Math.subtractExact(reservation.reservedUnits(),
                Math.addExact(reservation.releasedUnits(), reservation.settledUnits()))
                : AccountMarginReleaseMath.releaseForExecuted(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.settledUnits(), quantitySteps, remainingQuantitySteps);
        if (amountUnits <= 0L) {
            return 0L;
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
        return amountUnits;
    }

    private MarginUsage marginUsage(long orderId) {
        MarginUsage tradeUsage = jdbcTemplate.query("""
                SELECT COALESCE(SUM(order_margin_consumed_units), 0) AS consumed_units,
                       COALESCE(SUM(order_margin_released_units), 0) AS released_units
                  FROM account_trade_settlement_sides
                 WHERE order_id = ?
                """, (rs, rowNum) -> new MarginUsage(
                rs.getLong("consumed_units"), rs.getLong("released_units")), orderId).getFirst();
        Long commandReleased = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM((result_payload ->> 'releasedUnits')::bigint), 0)
                  FROM account_commands
                 WHERE command_type = 'ORDER_RELEASE'
                   AND source_reference = ?
                   AND status = 'APPLIED'
                   AND result_payload ->> 'releasedUnits' IS NOT NULL
                """, Long.class, String.valueOf(orderId));
        return new MarginUsage(tradeUsage.consumedUnits(),
                Math.addExact(tradeUsage.releasedUnits(), commandReleased == null ? 0L : commandReleased));
    }

    private SpotReservation lockSpot(long orderId) {
        return jdbcTemplate.query("""
                SELECT user_id, asset, reserved_units, settled_units, released_units
                  FROM account_spot_order_reservations
                 WHERE order_id = ? AND status NOT IN ('RELEASED', 'SETTLED')
                 FOR UPDATE
                """, (rs, rowNum) -> new SpotReservation(
                rs.getLong("user_id"), rs.getString("asset"), rs.getLong("reserved_units"),
                rs.getLong("settled_units"), rs.getLong("released_units")), orderId)
                .stream().findFirst().orElse(null);
    }

    private void requireAccountScope(ProductLine productLine, AccountType accountType) {
        ProductLine expected = accountType.productLine()
                .orElseThrow(() -> new IllegalStateException("order reservation requires a product account"));
        if (productLine != expected) {
            throw new IllegalStateException("order reservation account does not match product line");
        }
    }

    private void requireReservationScope(ProductLine productLine,
                                         long commandUserId,
                                         long reservationUserId,
                                         AccountType accountType,
                                         long orderId) {
        if (reservationUserId != commandUserId) {
            throw new IllegalStateException("account reservation user mismatch " + orderId);
        }
        requireAccountScope(productLine, accountType);
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
                       SET locked_units = locked_units - ?, available_units = available_units + ?, updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ? AND locked_units >= ?
                    """, amountUnits, amountUnits, Timestamp.from(now), accountType, userId, asset, amountUnits);
        } else {
            rows = jdbcTemplate.update("""
                    UPDATE account_balances
                       SET locked_units = locked_units - ?, available_units = available_units + ?, updated_at = ?
                     WHERE user_id = ? AND asset = ? AND locked_units >= ?
                    """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        }
        requireSingleRow(rows, "account locked balance release");
    }

    private boolean usesProductBalance(String accountType) {
        return !USDT_PERPETUAL.equals(accountType);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private record MarginUsage(long consumedUnits, long releasedUnits) {
    }

    private record SpotReservation(
            long userId,
            String asset,
            long reservedUnits,
            long settledUnits,
            long releasedUnits) {
    }
}
