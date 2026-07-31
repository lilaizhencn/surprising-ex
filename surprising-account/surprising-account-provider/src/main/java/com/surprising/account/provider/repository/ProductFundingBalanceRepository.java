package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.model.FundingBalanceState;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 原子维护产品账户的资金费余额与亏空。
 *
 * <p>不可拆原因：account_product_balances 与 account_product_deficits 必须按固定顺序在同一事务快照中
 * 一起锁定，资金费借记才能根据可用、锁定、亏空及已预留亏空计算唯一结果。拆成互不协调的仓储调用会
 * 引入锁顺序漂移和并发资金覆盖错误。该逻辑只服务在线资金结算，不提供后台对账或运营报表。</p>
 */
@Repository
public class ProductFundingBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductFundingBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FundingBalanceState lockOrCreate(AccountType accountType, long userId, String asset, Instant now) {
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
        return jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_product_balances b
                  JOIN account_product_deficits d USING (account_type, user_id, asset)
                 WHERE b.account_type = ? AND b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new FundingBalanceState(
                rs.getLong("available_units"), rs.getLong("locked_units"), rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), accountType.name(), userId, asset);
    }

    public void update(AccountType accountType,
                       long userId,
                       String asset,
                       FundingBalanceState state,
                       Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE account_product_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, state.availableUnits(), state.lockedUnits(), Timestamp.from(now),
                accountType.name(), userId, asset), "资金费产品账户余额更新");
        requireSingle(jdbcTemplate.update("""
                UPDATE account_product_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE account_type = ? AND user_id = ? AND asset = ?
                """, state.deficitUnits(), Timestamp.from(now), accountType.name(), userId, asset),
                "资金费产品账户亏空更新");
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响了 " + rows + " 行");
        }
    }
}
