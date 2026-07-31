package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.trading.api.model.MarginMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 原子维护兼容账户的在线损益结算状态。
 *
 * <p>不可拆原因：account_balances 与 account_deficits 必须按固定顺序一起锁定，损益和强平费才能基于
 * 可用余额、锁定余额、亏空及预留亏空计算唯一结果；高频快速路径还必须把余额更新与
 * account_ledger_entries 的幂等写入合成一条语句，避免重复消费时先改余额后撞流水唯一键。
 * 这些查询只服务在线交易结算，禁止用于后台对账、时间线或运营报表。</p>
 */
@Repository
public class AccountSettlementBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountSettlementBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BalanceSettlementState lockOrCreate(long userId, String asset, Instant now) {
        ensure(userId, asset, now);
        return lock(userId, asset);
    }

    public void ensure(long userId, String asset, Instant now) {
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
    }

    public BalanceSettlementState lock(long userId, String asset) {
        return jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"), rs.getLong("locked_units"),
                rs.getLong("deficit_units"), rs.getLong("reserved_units")), userId, asset);
    }

    public void update(long userId,
                       String asset,
                       BalanceSettlementState current,
                       BalanceSettlementState next,
                       Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now), userId, asset),
                "兼容账户结算余额更新");
        if (current.deficitUnits() == next.deficitUnits()) {
            return;
        }
        requireSingle(jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), userId, asset),
                "兼容账户结算亏空更新");
    }

    public Optional<Long> tryApplyAvailableDebit(long userId,
                                                 String asset,
                                                 MarginMode marginMode,
                                                 long amountUnits,
                                                 Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                UPDATE account_balances b
                   SET available_units = b.available_units + ?, updated_at = ?
                 WHERE b.user_id = ? AND b.asset = ? AND b.available_units + ? >= 0
             RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_deficits d
                        WHERE d.user_id = b.user_id AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), userId, asset, amountUnits)
                .stream().findFirst();
    }

    public Optional<Long> trySettleAvailableAndLedger(long entryId,
                                                       long userId,
                                                       String asset,
                                                       long amountUnits,
                                                       MarginMode marginMode,
                                                       String referenceType,
                                                       String referenceId,
                                                       String reason,
                                                       Long tradeId,
                                                       Long orderId,
                                                       String symbol,
                                                       Long feeRatePpm,
                                                       Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        List<Long> rows = jdbcTemplate.query("""
                WITH updated_balance AS (
                    UPDATE account_balances b
                       SET available_units = b.available_units + ?, updated_at = ?
                     WHERE b.user_id = ? AND b.asset = ?
                       AND NOT EXISTS (
                           SELECT 1 FROM account_ledger_entries l
                            WHERE l.reference_type = ? AND l.reference_id = ?
                              AND l.user_id = ? AND l.asset = ?
                       )
                       AND (
                           (? < 0 AND ? = 'CROSS' AND b.available_units + ? >= 0)
                           OR
                           (? > 0 AND COALESCE((
                               SELECT d.deficit_units - d.reserved_units
                                 FROM account_deficits d
                                WHERE d.user_id = b.user_id AND d.asset = b.asset
                           ), 0) = 0)
                       )
                 RETURNING b.available_units + b.locked_units - COALESCE((
                           SELECT d.deficit_units FROM account_deficits d
                            WHERE d.user_id = b.user_id AND d.asset = b.asset
                       ), 0) AS balance_after_units
                ),
                inserted_ledger AS (
                    INSERT INTO account_ledger_entries (
                        entry_id, user_id, asset, amount_units, balance_after_units,
                        reference_type, reference_id, reason, trade_id, order_id, symbol,
                        fee_rate_ppm, created_at
                    )
                    SELECT ?, ?, ?, ?, balance_after_units, ?, ?, ?,
                           CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS TEXT),
                           CAST(? AS BIGINT), ?
                      FROM updated_balance
                    ON CONFLICT (reference_type, reference_id, user_id, asset) DO NOTHING
                    RETURNING balance_after_units
                )
                SELECT balance_after_units FROM inserted_ledger
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, timestamp, userId, asset,
                referenceType, referenceId, userId, asset,
                amountUnits, marginMode.name(), amountUnits, amountUnits,
                entryId, userId, asset, amountUnits, referenceType, referenceId, reason,
                tradeId, orderId, symbol, feeRatePpm, timestamp);
        return rows == null ? Optional.empty() : rows.stream().findFirst();
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响了 " + rows + " 行");
        }
    }
}
