package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.FundingBalanceState;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 原子维护兼容账户的资金费余额与亏空。
 *
 * <p>不可拆原因：account_balances 与 account_deficits 必须按固定顺序在同一事务快照中一起锁定，
 * 资金费借记才能根据可用、锁定、亏空及已预留亏空计算唯一结果。拆成互不协调的仓储调用会引入
 * 锁顺序漂移和并发资金覆盖错误。该逻辑只服务在线资金结算，不提供后台对账或运营报表。</p>
 */
@Repository
public class AccountFundingBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountFundingBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public FundingBalanceState lockOrCreate(long userId, String asset, Instant now) {
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
        return jdbcTemplate.queryForObject("""
                SELECT b.available_units, b.locked_units, d.deficit_units, d.reserved_units
                  FROM account_balances b
                  JOIN account_deficits d USING (user_id, asset)
                 WHERE b.user_id = ? AND b.asset = ?
                 FOR UPDATE OF b, d
                """, (rs, rowNum) -> new FundingBalanceState(
                rs.getLong("available_units"), rs.getLong("locked_units"), rs.getLong("deficit_units"),
                rs.getLong("reserved_units")), userId, asset);
    }

    public void update(long userId, String asset, FundingBalanceState state, Instant now) {
        requireSingle(jdbcTemplate.update("""
                UPDATE account_balances
                   SET available_units = ?, locked_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, state.availableUnits(), state.lockedUnits(), Timestamp.from(now), userId, asset),
                "资金费兼容账户余额更新");
        requireSingle(jdbcTemplate.update("""
                UPDATE account_deficits
                   SET deficit_units = ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                """, state.deficitUnits(), Timestamp.from(now), userId, asset),
                "资金费兼容账户亏空更新");
    }

    private void requireSingle(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + "影响了 " + rows + " 行");
        }
    }
}
