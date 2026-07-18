package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Conditionally settles the profitable target side of an ADL execution. */
@Repository
public class AccountAdlTargetSettlementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountAdlTargetSettlementRepository(JdbcTemplate jdbcTemplate,
                                               AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public AdlTargetSettlementResult settle(ProductLine productLine,
                                            long targetUserId,
                                            String commandId,
                                            AdlTargetSettlementAccountCommand command,
                                            Instant now) {
        if (!productLine.isFundingProduct()) {
            throw new IllegalArgumentException("ADL target settlement requires a perpetual product line");
        }
        PositionState position;
        try {
            position = lockPosition(productLine, targetUserId, command);
        } catch (StaleAdlTargetException ex) {
            return AdlTargetSettlementResult.stale();
        }
        if (position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()) {
            return AdlTargetSettlementResult.stale();
        }
        long absQuantity = Math.absExact(position.signedQuantitySteps());
        if (command.closeQuantitySteps() > absQuantity) {
            return AdlTargetSettlementResult.stale();
        }
        long fullProfit = Math.max(0L, PerpetualContractMath.unrealizedPnlUnits(
                position.contractType(), position.signedQuantitySteps(), position.entryPriceTicks(),
                command.markPriceTicks(), position.notionalMultiplierUnits(), position.priceTickUnits(),
                position.settleScaleUnits()));
        long realizedProfit = proportional(fullProfit, command.closeQuantitySteps(), absQuantity);
        if (realizedProfit != command.expectedRealizedProfitUnits()
                || command.coveredUnits() > realizedProfit) {
            return AdlTargetSettlementResult.stale();
        }
        if (hasDeficit(productLine, targetUserId, command.asset())) {
            return AdlTargetSettlementResult.stale();
        }

        long nextAbs = Math.subtractExact(absQuantity, command.closeQuantitySteps());
        long nextSigned = position.signedQuantitySteps() > 0 ? nextAbs : Math.negateExact(nextAbs);
        updatePosition(productLine, targetUserId, command, position, nextSigned, realizedProfit, now);
        releasePositionMargin(productLine, targetUserId, command, absQuantity, now);
        long afterProfit = creditAvailable(productLine, targetUserId, command.asset(), realizedProfit, now);
        insertLedger(productLine, targetUserId, command.asset(), realizedProfit, afterProfit,
                "ADL_REALIZED_PNL", commandId, "ADL_POSITION_DELEVERAGED", now);
        long afterTransfer = debitAvailable(productLine, targetUserId, command.asset(), command.coveredUnits(), now);
        insertLedger(productLine, targetUserId, command.asset(), Math.negateExact(command.coveredUnits()),
                afterTransfer, "ADL_TRANSFER", commandId, "ADL_DEFICIT_TRANSFER", now);
        updateOpenInterest(productLine, command.symbol(), position.signedQuantitySteps(), nextSigned, now);
        return new AdlTargetSettlementResult(true, realizedProfit, command.coveredUnits(), nextSigned);
    }

    private PositionState lockPosition(ProductLine productLine,
                                       long userId,
                                       AdlTargetSettlementAccountCommand command) {
        return jdbcTemplate.query("""
                SELECT p.instrument_version, p.signed_quantity_steps, p.entry_price_ticks,
                       p.entry_value_ticks, p.realized_pnl_units,
                       i.contract_type, i.notional_multiplier_units, i.price_tick_units,
                       s.scale_units AS settle_scale_units
                  FROM account_positions p
                  JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version
                  JOIN account_asset_scales s ON s.asset = i.settle_asset
                 WHERE p.product_line = ? AND p.user_id = ? AND p.symbol = ?
                   AND p.margin_mode = ? AND p.position_side = ?
                   AND i.settle_asset = ?
                 FOR UPDATE OF p
                """, (rs, rowNum) -> new PositionState(
                rs.getLong("instrument_version"), rs.getLong("signed_quantity_steps"),
                rs.getLong("entry_price_ticks"), rs.getLong("entry_value_ticks"),
                rs.getLong("realized_pnl_units"), ContractType.valueOf(rs.getString("contract_type")),
                rs.getLong("notional_multiplier_units"), rs.getLong("price_tick_units"),
                rs.getLong("settle_scale_units")), productLine.name(), userId, command.symbol(),
                command.marginMode().name(), command.positionSide().name(), command.asset())
                .stream().findFirst().orElseThrow(() -> new StaleAdlTargetException("ADL target position is missing"));
    }

    private void updatePosition(ProductLine productLine,
                                long userId,
                                AdlTargetSettlementAccountCommand command,
                                PositionState position,
                                long nextSigned,
                                long realizedProfit,
                                Instant now) {
        long nextEntryValue = nextSigned == 0 ? 0L : Math.subtractExact(position.entryValueTicks(),
                proportional(position.entryValueTicks(), command.closeQuantitySteps(),
                        Math.absExact(position.signedQuantitySteps())));
        int rows = jdbcTemplate.update("""
                UPDATE account_positions
                   SET signed_quantity_steps = ?,
                       instrument_version = CASE WHEN ? = 0 THEN NULL ELSE instrument_version END,
                       entry_price_ticks = CASE WHEN ? = 0 THEN 0 ELSE entry_price_ticks END,
                       entry_value_ticks = ?,
                       realized_pnl_units = realized_pnl_units + ?,
                       updated_at = ?
                 WHERE product_line = ? AND user_id = ? AND symbol = ?
                   AND margin_mode = ? AND position_side = ?
                   AND signed_quantity_steps = ? AND entry_price_ticks = ?
                """, nextSigned, nextSigned, nextSigned, nextEntryValue, realizedProfit, Timestamp.from(now),
                productLine.name(), userId, command.symbol(), command.marginMode().name(),
                command.positionSide().name(), position.signedQuantitySteps(), position.entryPriceTicks());
        requireSingleRow(rows, "ADL target position update");
    }

    private void releasePositionMargin(ProductLine productLine,
                                       long userId,
                                       AdlTargetSettlementAccountCommand command,
                                       long previousAbsQuantity,
                                       Instant now) {
        List<Long> margins = jdbcTemplate.query("""
                SELECT margin_units
                  FROM account_position_margins
                 WHERE product_line = ? AND user_id = ? AND symbol = ? AND asset = ?
                   AND margin_mode = ? AND position_side = ? AND margin_units > 0
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("margin_units"), productLine.name(), userId, command.symbol(),
                command.asset(), command.marginMode().name(), command.positionSide().name());
        for (long marginUnits : margins) {
            long releaseUnits = proportional(marginUnits, command.closeQuantitySteps(), previousAbsQuantity);
            if (releaseUnits <= 0) {
                continue;
            }
            ensureBalance(productLine, userId, command.asset(), now);
            int balanceRows = usesProductAccount(productLine)
                    ? jdbcTemplate.update("""
                        UPDATE account_product_balances
                           SET locked_units = locked_units - ?,
                               available_units = available_units + ?,
                               updated_at = ?
                         WHERE account_type = ? AND user_id = ? AND asset = ? AND locked_units >= ?
                        """, releaseUnits, releaseUnits, Timestamp.from(now), productLine.accountTypeCode(),
                        userId, command.asset(), releaseUnits)
                    : jdbcTemplate.update("""
                        UPDATE account_balances
                           SET locked_units = locked_units - ?,
                               available_units = available_units + ?,
                               updated_at = ?
                         WHERE user_id = ? AND asset = ? AND locked_units >= ?
                        """, releaseUnits, releaseUnits, Timestamp.from(now), userId, command.asset(), releaseUnits);
            requireSingleRow(balanceRows, "ADL target margin balance release");
            int marginRows = jdbcTemplate.update("""
                    UPDATE account_position_margins
                       SET margin_units = margin_units - ?, updated_at = ?
                     WHERE product_line = ? AND user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ? AND position_side = ? AND margin_units >= ?
                    """, releaseUnits, Timestamp.from(now), productLine.name(), userId, command.symbol(),
                    command.asset(), command.marginMode().name(), command.positionSide().name(), releaseUnits);
            requireSingleRow(marginRows, "ADL target position margin release");
            jdbcTemplate.update("""
                    DELETE FROM account_position_margins
                     WHERE product_line = ? AND user_id = ? AND symbol = ? AND asset = ?
                       AND margin_mode = ? AND position_side = ? AND margin_units = 0
                    """, productLine.name(), userId, command.symbol(), command.asset(),
                    command.marginMode().name(), command.positionSide().name());
        }
    }

    private boolean hasDeficit(ProductLine productLine, long userId, String asset) {
        Long deficit = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    SELECT deficit_units
                      FROM account_product_deficits
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                     FOR UPDATE
                    """, (rs, rowNum) -> rs.getLong(1), productLine.accountTypeCode(), userId, asset)
                    .stream().findFirst().orElse(0L)
                : jdbcTemplate.query("""
                    SELECT deficit_units
                      FROM account_deficits
                     WHERE user_id = ? AND asset = ?
                     FOR UPDATE
                    """, (rs, rowNum) -> rs.getLong(1), userId, asset)
                    .stream().findFirst().orElse(0L);
        return deficit > 0;
    }

    private long creditAvailable(ProductLine productLine,
                                 long userId,
                                 String asset,
                                 long amountUnits,
                                 Instant now) {
        ensureBalance(productLine, userId, asset, now);
        List<Long> rows = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    UPDATE account_product_balances
                       SET available_units = available_units + ?, updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                 RETURNING available_units + locked_units
                    """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                    productLine.accountTypeCode(), userId, asset)
                : jdbcTemplate.query("""
                    UPDATE account_balances
                       SET available_units = available_units + ?, updated_at = ?
                     WHERE user_id = ? AND asset = ?
                 RETURNING available_units + locked_units
                    """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now), userId, asset);
        return requireOne(rows, "ADL target profit credit");
    }

    private long debitAvailable(ProductLine productLine,
                                long userId,
                                String asset,
                                long amountUnits,
                                Instant now) {
        List<Long> rows = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    UPDATE account_product_balances
                       SET available_units = available_units - ?, updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ? AND available_units >= ?
                 RETURNING available_units + locked_units
                    """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                    productLine.accountTypeCode(), userId, asset, amountUnits)
                : jdbcTemplate.query("""
                    UPDATE account_balances
                       SET available_units = available_units - ?, updated_at = ?
                     WHERE user_id = ? AND asset = ? AND available_units >= ?
                 RETURNING available_units + locked_units
                    """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                    userId, asset, amountUnits);
        return requireOne(rows, "ADL target transfer debit");
    }

    private void ensureBalance(ProductLine productLine, long userId, String asset, Instant now) {
        if (usesProductAccount(productLine)) {
            jdbcTemplate.update("""
                    INSERT INTO account_product_balances (
                        account_type, user_id, asset, available_units, locked_units, updated_at
                    ) VALUES (?, ?, ?, 0, 0, ?)
                    ON CONFLICT (account_type, user_id, asset) DO NOTHING
                    """, productLine.accountTypeCode(), userId, asset, Timestamp.from(now));
        } else {
            jdbcTemplate.update("""
                    INSERT INTO account_balances (user_id, asset, available_units, locked_units, updated_at)
                    VALUES (?, ?, 0, 0, ?)
                    ON CONFLICT (user_id, asset) DO NOTHING
                    """, userId, asset, Timestamp.from(now));
        }
    }

    private void insertLedger(ProductLine productLine,
                              long userId,
                              String asset,
                              long amountUnits,
                              long balanceAfter,
                              String referenceType,
                              String commandId,
                              String reason,
                              Instant now) {
        int rows = usesProductAccount(productLine)
                ? jdbcTemplate.update("""
                    INSERT INTO account_product_ledger_entries (
                        entry_id, account_type, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, account_type, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY),
                    productLine.accountTypeCode(),
                    userId, asset, amountUnits, balanceAfter, referenceType, commandId, reason, Timestamp.from(now))
                : jdbcTemplate.update("""
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    """, sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY),
                    userId, asset, amountUnits,
                    balanceAfter, referenceType, commandId, reason, Timestamp.from(now));
        requireSingleRow(rows, "ADL target ledger insert");
    }

    private void updateOpenInterest(ProductLine productLine,
                                    String symbol,
                                    long previousSigned,
                                    long nextSigned,
                                    Instant now) {
        long longDelta = Math.subtractExact(Math.max(nextSigned, 0L), Math.max(previousSigned, 0L));
        long previousShort = previousSigned < 0 ? Math.negateExact(previousSigned) : 0L;
        long nextShort = nextSigned < 0 ? Math.negateExact(nextSigned) : 0L;
        long shortDelta = Math.subtractExact(nextShort, previousShort);
        jdbcTemplate.update("""
                INSERT INTO trading_symbol_open_interest (
                    product_line, symbol, long_quantity_steps, short_quantity_steps,
                    open_quantity_steps, updated_at
                ) VALUES (?, ?, 0, 0, 0, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, productLine.name(), symbol, Timestamp.from(now));
        int rows = jdbcTemplate.update("""
                UPDATE trading_symbol_open_interest
                   SET long_quantity_steps = long_quantity_steps + ?,
                       short_quantity_steps = short_quantity_steps + ?,
                       open_quantity_steps = GREATEST(long_quantity_steps + ?, short_quantity_steps + ?),
                       updated_at = ?
                 WHERE product_line = ? AND symbol = ?
                   AND long_quantity_steps + ? >= 0 AND short_quantity_steps + ? >= 0
                """, longDelta, shortDelta, longDelta, shortDelta, Timestamp.from(now),
                productLine.name(), symbol, longDelta, shortDelta);
        requireSingleRow(rows, "ADL open interest update");
    }

    private long proportional(long units, long numerator, long denominator) {
        return BigInteger.valueOf(units)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }

    private long requireOne(List<Long> rows, String operation) {
        if (rows == null || rows.size() != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
        return rows.getFirst();
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }

    private record PositionState(
            long instrumentVersion,
            long signedQuantitySteps,
            long entryPriceTicks,
            long entryValueTicks,
            long realizedPnlUnits,
            ContractType contractType,
            long notionalMultiplierUnits,
            long priceTickUnits,
            long settleScaleUnits) {
    }

    public record AdlTargetSettlementResult(
            boolean applied,
            long realizedProfitUnits,
            long coveredUnits,
            long nextSignedQuantitySteps) {

        private static AdlTargetSettlementResult stale() {
            return new AdlTargetSettlementResult(false, 0L, 0L, 0L);
        }
    }

    private static final class StaleAdlTargetException extends RuntimeException {
        private StaleAdlTargetException(String message) {
            super(message);
        }
    }
}
