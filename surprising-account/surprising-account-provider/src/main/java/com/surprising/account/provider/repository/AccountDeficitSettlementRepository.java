package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns deficit reservations used to coordinate insurance and ADL without cross-user locks. */
@Repository
public class AccountDeficitSettlementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AccountSequenceRepository sequenceRepository;

    public AccountDeficitSettlementRepository(JdbcTemplate jdbcTemplate,
                                              AccountSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public boolean reserve(ProductLine productLine,
                           long userId,
                           String asset,
                           long amountUnits,
                           Instant now) {
        List<Long> rows = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    UPDATE account_product_deficits
                       SET reserved_units = reserved_units + ?, updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND deficit_units - reserved_units >= ?
                 RETURNING deficit_units - reserved_units AS available_deficit_units
                    """, (rs, rowNum) -> rs.getLong("available_deficit_units"),
                    amountUnits, Timestamp.from(now), productLine.accountTypeCode(), userId, asset, amountUnits)
                : jdbcTemplate.query("""
                    UPDATE account_deficits
                       SET reserved_units = reserved_units + ?, updated_at = ?
                     WHERE user_id = ? AND asset = ?
                       AND deficit_units - reserved_units >= ?
                 RETURNING deficit_units - reserved_units AS available_deficit_units
                    """, (rs, rowNum) -> rs.getLong("available_deficit_units"),
                    amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        return rows != null && !rows.isEmpty();
    }

    public long finalizeReservation(ProductLine productLine,
                                    long userId,
                                    String asset,
                                    long amountUnits,
                                    String commandId,
                                    String referenceType,
                                    String reason,
                                    Instant now) {
        List<Long> remainingRows = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    UPDATE account_product_deficits
                       SET deficit_units = deficit_units - ?,
                           reserved_units = reserved_units - ?,
                           updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND deficit_units >= ? AND reserved_units >= ?
                 RETURNING deficit_units
                    """, (rs, rowNum) -> rs.getLong("deficit_units"),
                    amountUnits, amountUnits, Timestamp.from(now), productLine.accountTypeCode(),
                    userId, asset, amountUnits, amountUnits)
                : jdbcTemplate.query("""
                    UPDATE account_deficits
                       SET deficit_units = deficit_units - ?,
                           reserved_units = reserved_units - ?,
                           updated_at = ?
                     WHERE user_id = ? AND asset = ?
                       AND deficit_units >= ? AND reserved_units >= ?
                 RETURNING deficit_units
                    """, (rs, rowNum) -> rs.getLong("deficit_units"),
                    amountUnits, amountUnits, Timestamp.from(now), userId, asset, amountUnits, amountUnits);
        if (remainingRows == null || remainingRows.size() != 1) {
            throw new IllegalStateException("deficit reservation is missing for " + commandId);
        }
        long remaining = remainingRows.getFirst();
        long balanceAfter = equity(productLine, userId, asset, remaining);
        int ledgerRows = usesProductAccount(productLine)
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
        if (ledgerRows != 1) {
            throw new IllegalStateException("failed to insert deficit settlement ledger " + commandId);
        }
        return remaining;
    }

    public long releaseReservation(ProductLine productLine,
                                   long userId,
                                   String asset,
                                   long amountUnits,
                                   Instant now) {
        List<Long> rows = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    UPDATE account_product_deficits
                       SET reserved_units = reserved_units - ?, updated_at = ?
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                       AND reserved_units >= ?
                 RETURNING deficit_units - reserved_units AS available_deficit_units
                    """, (rs, rowNum) -> rs.getLong("available_deficit_units"),
                    amountUnits, Timestamp.from(now), productLine.accountTypeCode(), userId, asset, amountUnits)
                : jdbcTemplate.query("""
                    UPDATE account_deficits
                       SET reserved_units = reserved_units - ?, updated_at = ?
                     WHERE user_id = ? AND asset = ? AND reserved_units >= ?
                 RETURNING deficit_units - reserved_units AS available_deficit_units
                    """, (rs, rowNum) -> rs.getLong("available_deficit_units"),
                    amountUnits, Timestamp.from(now), userId, asset, amountUnits);
        if (rows == null || rows.size() != 1) {
            throw new IllegalStateException("deficit reservation release is missing");
        }
        return rows.getFirst();
    }

    private long equity(ProductLine productLine, long userId, String asset, long remainingDeficit) {
        Long balance = usesProductAccount(productLine)
                ? jdbcTemplate.query("""
                    SELECT available_units + locked_units
                      FROM account_product_balances
                     WHERE account_type = ? AND user_id = ? AND asset = ?
                    """, (rs, rowNum) -> rs.getLong(1), productLine.accountTypeCode(), userId, asset)
                    .stream().findFirst().orElse(0L)
                : jdbcTemplate.query("""
                    SELECT available_units + locked_units
                      FROM account_balances
                     WHERE user_id = ? AND asset = ?
                    """, (rs, rowNum) -> rs.getLong(1), userId, asset)
                    .stream().findFirst().orElse(0L);
        return Math.subtractExact(balance, remainingDeficit);
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }
}
