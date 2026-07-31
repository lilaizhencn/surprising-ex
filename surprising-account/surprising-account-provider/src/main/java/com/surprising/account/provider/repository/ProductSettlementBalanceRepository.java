package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.model.BalanceSettlementState;
import com.surprising.trading.api.model.MarginMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 原子维护产品账户的在线损益结算状态。
 *
 * <p>不可拆原因：account_product_balances 与 account_product_deficits 必须按固定顺序一起锁定，
 * 损益和强平费才能基于可用余额、锁定余额、亏空及预留亏空计算唯一结果。拆开锁定会造成并发结算
 * 覆盖资金状态。该逻辑只服务在线交易结算，禁止用于后台对账、时间线或运营报表。</p>
 */
@Repository
public class ProductSettlementBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductSettlementBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BalanceSettlementState lockOrCreate(AccountType accountType,
                                               long userId,
                                               String asset,
                                               Instant now) {
        ensure(accountType, userId, asset, now);
        return lock(accountType, userId, asset);
    }

    public void ensure(AccountType accountType,
                       long userId,
                       String asset,
                       Instant now) {
        jdbcTemplate.update("""
                INSERT INTO account_product_balances (
                    account_type, user_id, asset, available_units, locked_units, updated_at
                ) VALUES (?, ?, ?, 0, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
        jdbcTemplate.update("""
                INSERT INTO account_product_deficits (
                    account_type, user_id, asset, deficit_units, updated_at
                ) VALUES (?, ?, ?, 0, ?)
                ON CONFLICT (account_type, user_id, asset) DO NOTHING
                """, accountType.name(), userId, asset, Timestamp.from(now));
    }

    public BalanceSettlementState lock(AccountType accountType, long userId, String asset) {
        return jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new BalanceSettlementState(
                rs.getLong("available_units"), rs.getLong("locked_units"),
                rs.getLong("deficit_units"), rs.getLong("reserved_units")),
                accountType.name(), userId, asset);
    }

    public void update(AccountType accountType,
                       long userId,
                       String asset,
                       BalanceSettlementState current,
                       BalanceSettlementState next,
                       Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, next.availableUnits(), next.lockedUnits(), Timestamp.from(now),
                accountType.name(), userId, asset), "产品账户结算余额更新");
        if (current.deficitUnits() == next.deficitUnits()) {
            return;
        }
        requireSingle(jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, next.deficitUnits(), Timestamp.from(now), accountType.name(), userId, asset),
                "产品账户结算亏空更新");
    }

    public Optional<Long> tryApplyAvailableDebit(AccountType accountType,
                                                 long userId,
                                                 String asset,
                                                 MarginMode marginMode,
                                                 long amountUnits,
                                                 Instant now) {
        if (amountUnits >= 0 || marginMode != MarginMode.CROSS) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                UPDATE account_product_balances b
                   SET available_units = b.available_units + ?, updated_at = ?
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                   AND b.available_units + ? >= 0
             RETURNING b.available_units + b.locked_units - COALESCE((
                       SELECT d.deficit_units
                         FROM account_product_deficits d
                        WHERE d.account_type = b.account_type
                          AND d.user_id = b.user_id AND d.asset = b.asset
                   ), 0) AS balance_after_units
                """, (rs, rowNum) -> rs.getLong("balance_after_units"),
                amountUnits, Timestamp.from(now), accountType.name(), userId, asset, amountUnits)
                .stream().findFirst();
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响了 " + rows + " 行");
        }
    }
}
