package com.surprising.account.provider.repository;

import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Authoritative funding-payment writer. This class deliberately lives in the account module so
 * funding calculation nodes never mutate account balances, deficits, margins, or ledgers.
 */
@Repository
public class AccountFundingSettlementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountFundingSettlementRepository(JdbcTemplate jdbcTemplate,
                                              AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public long apply(ProductLine productLine,
                      long userId,
                      String commandId,
                      FundingSettlementAccountCommand payment,
                      Instant now) {
        if (!productLine.isFundingProduct()) {
            throw new IllegalArgumentException("funding settlement requires a perpetual product line");
        }
        return usesProductAccount(productLine)
                ? applyProductAccount(productLine, userId, commandId, payment, now)
                : applyLegacyAccount(productLine, userId, commandId, payment, now);
    }

    private long applyLegacyAccount(ProductLine productLine,
                                    long userId,
                                    String commandId,
                                    FundingSettlementAccountCommand payment,
                                    Instant now) {
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_ledger_entries (
                    entry_id, user_id, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, 0, 'FUNDING', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                userId, payment.asset(),
                payment.amountUnits(), commandId, reason(payment.amountUnits()), Timestamp.from(now));
        requireSingleRow(ledgerRows, "funding account ledger insert");
        long balanceAfter = applyBalance(productLine, userId, payment, now);
        int ledgerUpdateRows = jdbcTemplate.update("""
                UPDATE account_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'FUNDING'
                   AND reference_id = ?
                   AND user_id = ?
                   AND asset = ?
                """, balanceAfter, commandId, userId, payment.asset());
        requireSingleRow(ledgerUpdateRows, "funding account ledger balance update");
        return balanceAfter;
    }

    private long applyProductAccount(ProductLine productLine,
                                     long userId,
                                     String commandId,
                                     FundingSettlementAccountCommand payment,
                                     Instant now) {
        String accountType = productLine.accountTypeCode();
        int ledgerRows = jdbcTemplate.update("""
                INSERT INTO account_product_ledger_entries (
                    entry_id, user_id, account_type, asset, amount_units, balance_after_units,
                    reference_type, reference_id, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, 'FUNDING', ?, ?, ?)
                ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                userId, accountType,
                payment.asset(), payment.amountUnits(), commandId, reason(payment.amountUnits()),
                Timestamp.from(now));
        requireSingleRow(ledgerRows, "funding product account ledger insert");
        long balanceAfter = applyProductBalance(productLine, userId, payment, now);
        int ledgerUpdateRows = jdbcTemplate.update("""
                UPDATE account_product_ledger_entries
                   SET balance_after_units = ?
                 WHERE reference_type = 'FUNDING'
                   AND reference_id = ?
                   AND user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, balanceAfter, commandId, userId, accountType, payment.asset());
        requireSingleRow(ledgerUpdateRows, "funding product account ledger balance update");
        return balanceAfter;
    }

    private long applyBalance(ProductLine productLine,
                              long userId,
                              FundingSettlementAccountCommand payment,
                              Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                VALUES (?, ?, 0, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, payment.asset(), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_deficits (user_id, asset, deficit_units, updated_at)
                VALUES (?, ?, 0, ?)
                ON CONFLICT (user_id, asset) DO NOTHING
                """, userId, payment.asset(), Timestamp.from(now));
        List<PositionMargin> lockedMargins = lockDebitMargins(productLine, userId, payment);
        long maxLockedDebit = sumMargins(lockedMargins);
        BalanceState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new BalanceState(
                rs.getLong("available_units"), rs.getLong("locked_units"), rs.getLong("deficit_units"),
                rs.getLong("reserved_units")),
                userId, payment.asset());
        BalanceState next = applyPayment(current, payment.marginMode(), payment.amountUnits(), maxLockedDebit);
        reducePositionMargins(userId, payment.asset(),
                Math.subtractExact(current.lockedUnits(), next.lockedUnits()), lockedMargins, now);
        requireSingleRow(jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, payment.asset()),
                "funding account balance update");
        requireSingleRow(jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), userId, payment.asset()),
                "funding account deficit update");
        return netBalance(next);
    }

    private long applyProductBalance(ProductLine productLine,
                                     long userId,
                                     FundingSettlementAccountCommand payment,
                                     Instant now) {
        String accountType = productLine.accountTypeCode();
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType, userId, payment.asset(), Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (
                    account_type, user_id, asset, deficit_units, updated_at
                ) VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType, userId, payment.asset(), Timestamp.from(now));
        List<PositionMargin> lockedMargins = lockDebitMargins(productLine, userId, payment);
        long maxLockedDebit = sumMargins(lockedMargins);
        BalanceState current = jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new BalanceState(
                rs.getLong("available_units"), rs.getLong("locked_units"), rs.getLong("deficit_units"),
                rs.getLong("reserved_units")),
                accountType, userId, payment.asset());
        BalanceState next = applyPayment(current, payment.marginMode(), payment.amountUnits(), maxLockedDebit);
        reducePositionMargins(userId, payment.asset(),
                Math.subtractExact(current.lockedUnits(), next.lockedUnits()), lockedMargins, now);
        requireSingleRow(jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now),
                accountType, userId, payment.asset()), "funding product balance update");
        requireSingleRow(jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), accountType, userId, payment.asset()),
                "funding product deficit update");
        return netBalance(next);
    }

    private List<PositionMargin> lockDebitMargins(ProductLine productLine,
                                                  long userId,
                                                  FundingSettlementAccountCommand payment) {
        if (payment.amountUnits() >= 0) {
            return List.of();
        }
        if (payment.marginMode() == MarginMode.ISOLATED) {
            return jdbcTemplate.query("""
                    SELECT product_line, symbol, margin_mode, position_side, margin_units
                      FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND product_line = ?
                       AND margin_mode = ? AND position_side = ? AND asset = ?
                       AND margin_units > 0
                     ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                     FOR UPDATE
                    """, (rs, rowNum) -> margin(rs), userId, payment.symbol(), productLine.name(),
                    payment.marginMode().name(), payment.positionSide().name(), payment.asset());
        }
        return jdbcTemplate.query("""
                SELECT product_line, symbol, margin_mode, position_side, margin_units
                  FROM account_position_margins
                 WHERE user_id = ? AND product_line = ? AND asset = ?
                   AND margin_mode = ? AND position_side = ?
                   AND margin_units > 0
                 ORDER BY updated_at ASC, symbol ASC, margin_mode ASC, position_side ASC
                 FOR UPDATE
                """, (rs, rowNum) -> margin(rs), userId, productLine.name(), payment.asset(),
                payment.marginMode().name(), payment.positionSide().name());
    }

    private BalanceState applyPayment(BalanceState current,
                                      MarginMode marginMode,
                                      long amountUnits,
                                      long maxLockedDebitUnits) {
        if (amountUnits > 0) {
            long availableDeficit = Math.subtractExact(
                    current.deficitUnits(), current.reservedDeficitUnits());
            long deficitOffset = Math.min(availableDeficit, amountUnits);
            return new BalanceState(
                    Math.addExact(current.availableUnits(), Math.subtractExact(amountUnits, deficitOffset)),
                    current.lockedUnits(),
                    Math.subtractExact(current.deficitUnits(), deficitOffset),
                    current.reservedDeficitUnits());
        }
        long availableInput = marginMode == MarginMode.ISOLATED ? 0L : current.availableUnits();
        long charge = Math.negateExact(amountUnits);
        long fromAvailable = Math.min(availableInput, charge);
        long remaining = Math.subtractExact(charge, fromAvailable);
        long fromLocked = Math.min(Math.min(current.lockedUnits(), Math.max(0L, maxLockedDebitUnits)), remaining);
        BalanceState calculated = new BalanceState(
                Math.subtractExact(availableInput, fromAvailable),
                Math.subtractExact(current.lockedUnits(), fromLocked),
                Math.addExact(current.deficitUnits(), Math.subtractExact(remaining, fromLocked)),
                current.reservedDeficitUnits());
        return marginMode == MarginMode.ISOLATED
                ? new BalanceState(current.availableUnits(), calculated.lockedUnits(), calculated.deficitUnits(),
                        calculated.reservedDeficitUnits())
                : calculated;
    }

    private void reducePositionMargins(long userId,
                                       String asset,
                                       long debitUnits,
                                       List<PositionMargin> lockedMargins,
                                       Instant now) {
        long remaining = debitUnits;
        for (PositionMargin margin : lockedMargins) {
            if (remaining <= 0) {
                break;
            }
            long debit = Math.min(margin.marginUnits(), remaining);
            requireSingleRow(jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?, updated_at = ?
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ? AND position_side = ? AND product_line = ?
                       AND margin_units >= ?
                    """, debit, Timestamp.from(now), userId, margin.symbol(), asset,
                    margin.marginMode().name(), margin.positionSide().name(), margin.productLine().name(), debit),
                    "funding position margin debit");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ? AND position_side = ? AND product_line = ?
                       AND margin_units = 0
                    """, userId, margin.symbol(), asset, margin.marginMode().name(),
                    margin.positionSide().name(), margin.productLine().name());
            remaining = Math.subtractExact(remaining, debit);
        }
        if (remaining != 0) {
            throw new IllegalStateException("insufficient position margin for funding locked debit");
        }
    }

    private PositionMargin margin(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionMargin(
                ProductLine.valueOf(rs.getString("product_line")),
                rs.getString("symbol"),
                MarginMode.fromNullableDbValue(rs.getString("margin_mode")),
                PositionSide.fromNullableDbValue(rs.getString("position_side")),
                rs.getLong("margin_units"));
    }

    private long sumMargins(List<PositionMargin> margins) {
        return margins.stream().mapToLong(PositionMargin::marginUnits).reduce(0L, Math::addExact);
    }

    private long netBalance(BalanceState state) {
        return Math.subtractExact(Math.addExact(state.availableUnits(), state.lockedUnits()), state.deficitUnits());
    }

    private String reason(long amountUnits) {
        return amountUnits >= 0 ? "FUNDING_RECEIVED" : "FUNDING_PAID";
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private record BalanceState(
            long availableUnits,
            long lockedUnits,
            long deficitUnits,
            long reservedDeficitUnits) {
    }

    private record PositionMargin(
            ProductLine productLine,
            String symbol,
            MarginMode marginMode,
            PositionSide positionSide,
            long marginUnits) {
    }
}
