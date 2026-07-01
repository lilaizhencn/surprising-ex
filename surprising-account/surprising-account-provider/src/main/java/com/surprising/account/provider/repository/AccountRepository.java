package com.surprising.account.provider.repository;

import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.PositionResponse;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.service.MarginTransferMath;
import com.surprising.account.provider.service.PnlSettlementMath;
import com.surprising.instrument.api.model.ContractType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountRepository(JdbcTemplate jdbcTemplate, AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public Optional<BalanceResponse> balance(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId, asset).stream().findFirst();
    }

    public List<BalanceResponse> balances(long userId) {
        return jdbcTemplate.query("""
                SELECT b.user_id, b.asset, b.available_units, b.locked_units,
                       b.available_units + b.locked_units - COALESCE(d.deficit_units, 0) AS equity_units,
                       b.updated_at
                  FROM account_balances b
                  LEFT JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ?
                 ORDER BY b.asset ASC
                """, (rs, rowNum) -> new BalanceResponse(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("equity_units"),
                rs.getTimestamp("updated_at").toInstant()), userId);
    }

    public BalanceResponse adjustBalance(long userId, String asset, long amountUnits, String referenceId, String reason) {
        Instant now = Instant.now();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'BALANCE_ADJUSTMENT', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, amountUnits,
                referenceId, reason, Timestamp.from(now));
        if (ledgerRows == 0) {
            requireDuplicateBalanceAdjustmentMatches(userId, asset, amountUnits, referenceId, reason);
            return balance(userId, asset)
                    .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but balance missing"));
        }
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        Long currentAvailable = jdbcTemplate.queryForObject("""
                SELECT available_units
                  FROM account_balances
                 WHERE user_id = ? AND asset = ?
                 FOR UPDATE
                """, Long.class, userId, asset);
        long nextAvailable = Math.addExact(currentAvailable == null ? 0L : currentAvailable, amountUnits);
        if (nextAvailable < 0) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, nextAvailable, Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "balance adjustment update");
        BalanceResponse updated = balance(userId, asset)
                .orElseThrow(() -> new IllegalStateException("balance not found after adjustment"));
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, updated.availableUnits(), referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "balance adjustment ledger update");
        return updated;
    }

    private void requireDuplicateBalanceAdjustmentMatches(long userId,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AdjustmentReference existing = jdbcTemplate.query("""
                SELECT amount_units, reason
                  FROM account_ledger_entries
                 WHERE reference_type = 'BALANCE_ADJUSTMENT'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new AdjustmentReference(
                rs.getLong("amount_units"),
                rs.getString("reason")), referenceId, userId, asset).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("duplicate balance adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate balance adjustment reference " + referenceId);
        }
    }

    public Optional<PositionResponse> position(long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ?
                """, (rs, rowNum) -> toPositionResponse(rs), userId, symbol).stream().findFirst();
    }

    public List<PositionResponse> positions(long userId) {
        return jdbcTemplate.query("""
                SELECT user_id, symbol, instrument_version, signed_quantity_steps,
                       entry_price_ticks, realized_pnl_units, updated_at
                  FROM account_positions
                 WHERE user_id = ?
                   AND signed_quantity_steps <> 0
                 ORDER BY symbol ASC
                """, (rs, rowNum) -> toPositionResponse(rs), userId);
    }

    public PositionState lockPosition(long userId, String symbol) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO account_positions (
                    user_id, symbol, instrument_version, signed_quantity_steps,
                    entry_price_ticks, realized_pnl_units, updated_at
                ) VALUES (?, ?, NULL, 0, 0, 0, ?)
                ON CONFLICT (user_id, symbol) DO NOTHING
                """, userId, symbol, Timestamp.from(now));
        return jdbcTemplate.queryForObject("""
                SELECT instrument_version, signed_quantity_steps, entry_price_ticks, realized_pnl_units
                  FROM account_positions
                 WHERE user_id = ? AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionState(
                rs.getLong("signed_quantity_steps"),
                longOrZero(rs, "instrument_version"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units")), userId, symbol);
    }

    public PositionResponse updatePosition(long userId, String symbol, PositionState state, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = ?,
                       entry_price_ticks = ?,
                       realized_pnl_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND symbol = ?
                """, state.signedQuantitySteps(), nullableVersion(state.instrumentVersion()),
                state.entryPriceTicks(), state.realizedPnlUnits(),
                Timestamp.from(now), userId, symbol);
        requireSingleRow(rows, "account position update");
        return position(userId, symbol)
                .orElseThrow(() -> new IllegalStateException("position not found after update"));
    }

    public ContractSpec contractSpec(String symbol, long instrumentVersion) {
        return jdbcTemplate.query("""
                SELECT i.version,
                       i.contract_type,
                       i.settle_asset,
                       i.notional_multiplier_units,
                       i.price_tick_units,
                       i.initial_margin_rate_ppm,
                       i.maker_fee_rate_ppm,
                       i.taker_fee_rate_ppm,
                       ss.scale_units AS settle_scale_units
                  FROM instruments i
                  JOIN account_asset_scales ss
                    ON ss.asset = i.settle_asset
                 WHERE i.symbol = ?
                   AND i.version = ?
                """, (rs, rowNum) -> new ContractSpec(
                rs.getLong("version"),
                ContractType.valueOf(rs.getString("contract_type")),
                rs.getString("settle_asset"),
                rs.getLong("notional_multiplier_units"),
                rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units"),
                rs.getLong("initial_margin_rate_ppm"),
                rs.getLong("maker_fee_rate_ppm"),
                rs.getLong("taker_fee_rate_ppm")), symbol, instrumentVersion).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("instrument contract spec not found for "
                        + symbol + " version " + instrumentVersion));
    }

    public boolean markTradeProcessing(long tradeId, String symbol) {
        int rows = jdbcTemplate.update("""
                INSERT INTO account_processed_trades (trade_id, symbol, processed_at)
                VALUES (?, ?, now())
                ON CONFLICT (symbol, trade_id) DO NOTHING
                """, tradeId, symbol);
        return rows == 1;
    }

    public void settleRealizedPnl(long userId,
                                  String asset,
                                  long orderId,
                                  long tradeId,
                                  long realizedPnlDeltaUnits,
                                  Instant now) {
        if (realizedPnlDeltaUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId;
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'TRADE_PNL', ?, 'REALIZED_PNL', ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, realizedPnlDeltaUnits,
                referenceId, Timestamp.from(now));
        requireSingleRow(ledgerRows, "trade pnl ledger insert");
        long balanceAfterUnits = applyPnlToBalance(userId, asset, realizedPnlDeltaUnits, now);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'TRADE_PNL'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "trade pnl ledger update");
    }

    public void settleTradeFee(long userId,
                               String asset,
                               long orderId,
                               long tradeId,
                               long feeDeltaUnits,
                               String reason,
                               Instant now) {
        if (feeDeltaUnits == 0) {
            return;
        }
        String referenceId = tradeId + ":" + orderId;
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'TRADE_FEE', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence("ledger-entry"), userId, asset, feeDeltaUnits,
                referenceId, reason, Timestamp.from(now));
        requireSingleRow(ledgerRows, "trade fee ledger insert");
        long balanceAfterUnits = applyPnlToBalance(userId, asset, feeDeltaUnits, now);
        int ledgerRowsAfter = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'TRADE_FEE'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfterUnits, referenceId, userId, asset);
        requireSingleRow(ledgerRowsAfter, "trade fee ledger update");
    }

    public void releaseOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   long closeSteps,
                                   boolean sweepRemainder,
                                   Instant now) {
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            requireReservationUnlessReduceOnly(orderId, userId, symbol);
            return;
        }
        long amountUnits = MarginTransferMath.orderMarginReleaseAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                closeSteps, sweepRemainder);
        releaseReservedMargin(orderId, reservation.userId(), reservation.asset(), amountUnits,
                "POSITION_REDUCED", now);
    }

    public void consumeOrderMargin(long orderId,
                                   long userId,
                                   String symbol,
                                   long openSteps,
                                   long actualMarginUnits,
                                   boolean sweepRemainder,
                                   Instant now) {
        if (openSteps <= 0) {
            return;
        }
        OrderMarginReservation reservation = lockOrderMarginReservation(orderId, userId, symbol);
        if (reservation == null) {
            throw new IllegalStateException("missing order margin reservation for opening fill " + orderId);
        }
        if (reservation.reduceOnly()) {
            throw new IllegalStateException("reduce-only order cannot consume opening margin " + orderId);
        }
        long allocatedUnits = MarginTransferMath.orderMarginConsumeAmount(reservation.reservedUnits(),
                reservation.releasedUnits(), reservation.positionMarginUnits(), reservation.orderQuantitySteps(),
                openSteps, sweepRemainder);
        long excessUnits = MarginTransferMath.excessOrderMarginUnits(allocatedUnits, actualMarginUnits);
        if (actualMarginUnits <= 0 && excessUnits <= 0) {
            return;
        }
        if (actualMarginUnits > 0) {
            int reservationRows = jdbcTemplate.update("""
                    UPDATE account_margin_reservations
                       SET position_margin_units = position_margin_units + ?,
                           status = CASE
                               WHEN released_units + position_margin_units + ? >= reserved_units THEN 'CONSUMED'
                               ELSE 'PARTIALLY_CONSUMED'
                           END,
                           reason = 'POSITION_OPENED',
                           updated_at = ?
                     WHERE order_id = ?
                       AND released_units + position_margin_units + ? <= reserved_units
                    """, actualMarginUnits, actualMarginUnits, Timestamp.from(now), orderId, actualMarginUnits);
            requireSingleRow(reservationRows, "order margin consumption");
            int positionMarginRows = jdbcTemplate.update("""
                    INSERT INTO account_position_margins (user_id, symbol, asset, margin_units, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, symbol, asset) DO UPDATE
                       SET margin_units = account_position_margins.margin_units + EXCLUDED.margin_units,
                           updated_at = EXCLUDED.updated_at
                    """, userId, symbol, reservation.asset(), actualMarginUnits, Timestamp.from(now));
            requireSingleRow(positionMarginRows, "position margin upsert");
        }
        releaseReservedMargin(orderId, reservation.userId(), reservation.asset(), excessUnits,
                "ORDER_PRICE_IMPROVEMENT", now);
    }

    public void releasePositionMargin(long userId,
                                      String symbol,
                                      long closeSteps,
                                      long positionAbsSteps,
                                      Instant now) {
        List<PositionMargin> margins = jdbcTemplate.query("""
                SELECT asset, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND symbol = ?
                   AND margin_units > 0
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(symbol, rs.getString("asset"), rs.getLong("margin_units")),
                userId, symbol);
        for (PositionMargin margin : margins) {
            long amountUnits = MarginTransferMath.positionMarginReleaseAmount(margin.marginUnits(),
                    closeSteps, positionAbsSteps);
            if (amountUnits <= 0) {
                continue;
            }
            releaseBalanceLock(userId, margin.asset(), amountUnits, now);
            int marginRows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_units >= ?
                    """, amountUnits, Timestamp.from(now), userId, symbol, margin.asset(), amountUnits);
            requireSingleRow(marginRows, "position margin release");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_units = 0
                    """, userId, symbol, margin.asset());
        }
    }

    private OrderMarginReservation lockOrderMarginReservation(long orderId, long userId, String symbol) {
        return jdbcTemplate.query("""
                SELECT r.user_id, r.asset, r.reserved_units, r.released_units,
                       r.position_margin_units, o.quantity_steps, o.reduce_only
                  FROM account_margin_reservations r
                  JOIN trading_orders o
                    ON o.order_id = r.order_id
                 WHERE r.order_id = ?
                   AND r.user_id = ?
                   AND r.symbol = ?
                 FOR UPDATE OF r
                """, (rs, rowNum) -> new OrderMarginReservation(
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("reserved_units"),
                rs.getLong("released_units"),
                rs.getLong("position_margin_units"),
                rs.getLong("quantity_steps"),
                rs.getBoolean("reduce_only")), orderId, userId, symbol).stream().findFirst().orElse(null);
    }

    private void requireReservationUnlessReduceOnly(long orderId, long userId, String symbol) {
        Boolean reduceOnly = jdbcTemplate.query("""
                SELECT reduce_only
                  FROM trading_orders
                 WHERE order_id = ?
                   AND user_id = ?
                   AND symbol = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getBoolean("reduce_only"), orderId, userId, symbol)
                .stream()
                .findFirst()
                .orElse(null);
        if (reduceOnly == null) {
            throw new IllegalStateException("order not found for account margin release " + orderId);
        }
        if (!reduceOnly) {
            throw new IllegalStateException("missing order margin reservation for closing fill " + orderId);
        }
    }

    private void releaseReservedMargin(long orderId,
                                       long userId,
                                       String asset,
                                       long amountUnits,
                                       String reason,
                                       Instant now) {
        if (amountUnits <= 0) {
            return;
        }
        releaseBalanceLock(userId, asset, amountUnits, now);
        int reservationRows = jdbcTemplate.update("""
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
        requireSingleRow(reservationRows, "reserved margin release");
    }

    private void releaseBalanceLock(long userId, String asset, long amountUnits, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET locked_units = locked_units - ?,
                       available_units = available_units + ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND locked_units >= ?
                """, amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows != 1) {
            throw new IllegalStateException("insufficient locked balance for margin release");
        }
    }

    private long applyPnlToBalance(long userId, String asset, long pnlUnits, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, asset, Timestamp.from(now));
        List<PositionMargin> lockedMargins = pnlUnits < 0 ? lockPositionMargins(userId, asset) : List.of();
        long maxLockedDebitUnits = lockedMargins.stream()
                .mapToLong(PositionMargin::marginUnits)
                .reduce(0L, Math::addExact);
        BalanceSettlementState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getLong("deficit_units")), userId, asset);
        BalanceSettlementState next = PnlSettlementMath.apply(current.availableUnits(), current.lockedUnits(),
                current.deficitUnits(), pnlUnits, maxLockedDebitUnits);
        long lockedDebitUnits = Math.subtractExact(current.lockedUnits(), next.lockedUnits());
        reducePositionMargins(userId, asset, lockedDebitUnits, lockedMargins, now);
        int balanceRows = jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?,
                       locked_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(balanceRows, "pnl balance update");
        int deficitRows = jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), userId, asset);
        requireSingleRow(deficitRows, "pnl deficit update");
        return PnlSettlementMath.netEquityUnits(next.availableUnits(), next.lockedUnits(), next.deficitUnits());
    }

    private List<PositionMargin> lockPositionMargins(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT symbol, asset, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND asset = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC
                 FOR UPDATE
                """, (rs, rowNum) -> new PositionMargin(
                rs.getString("symbol"),
                rs.getString("asset"),
                rs.getLong("margin_units")), userId, asset);
    }

    private void reducePositionMargins(long userId,
                                       String asset,
                                       long amountUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        long remaining = amountUnits;
        for (PositionMargin margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            int rows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_units >= ?
                    """, debit, Timestamp.from(now), userId, margin.symbol(), asset, debit);
            if (rows != 1) {
                throw new IllegalStateException("failed to reduce consumed position margin");
            }
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ? AND margin_units = 0
                    """, userId, margin.symbol(), asset);
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for locked debit");
        }
    }

    private PositionResponse toPositionResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionResponse(
                rs.getLong("user_id"),
                rs.getString("symbol"),
                longOrZero(rs, "instrument_version"),
                rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"),
                rs.getLong("realized_pnl_units"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private record OrderMarginReservation(
            long userId,
            String asset,
            long reservedUnits,
            long releasedUnits,
            long positionMarginUnits,
            long orderQuantitySteps,
            boolean reduceOnly) {
    }

    private record PositionMargin(String symbol, String asset, long marginUnits) {
    }

    private record AdjustmentReference(long amountUnits, String reason) {
    }

    private Long nullableVersion(long version) {
        return version <= 0 ? null : version;
    }

    private long longOrZero(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0L : value;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

}
