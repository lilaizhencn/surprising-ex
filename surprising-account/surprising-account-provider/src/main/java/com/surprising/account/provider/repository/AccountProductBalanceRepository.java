package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 产品账户余额的异步投影仓储。
 *
 * <p>账户命令热路径不调用本仓储。产品线和账户类型都是主键的一部分，避免不同产品线
 * 的用户分区同时修改同一条无产品边界的余额记录。</p>
 */
@Repository
public class AccountProductBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountProductBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BalanceRow> findByUser(AccountType accountType, long userId) {
        requireAccountType(accountType);
        return jdbcTemplate.query("""
                SELECT account_type, user_id, asset, available_units, locked_units, updated_at
                  FROM account_product_balances
                 WHERE account_type = ? AND user_id = ?
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new BalanceRow(
                AccountType.valueOf(rs.getString("account_type")),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("available_units"),
                rs.getLong("locked_units"),
                rs.getTimestamp("updated_at").toInstant()), accountType.name(), userId);
    }

    /** 只由异步完整快照投影调用，按产品账户原子替换余额列表。 */
    public void replaceProjection(AccountType accountType,
                                  long userId,
                                  List<PerpetualAccountStateUpdatedEvent.Balance> balances,
                                  Instant projectedAt) {
        requireAccountType(accountType);
        jdbcTemplate.update("""
                DELETE FROM account_product_balances
                 WHERE account_type = ? AND user_id = ?
                """, accountType.name(), userId);
        if (balances == null) {
            return;
        }
        for (PerpetualAccountStateUpdatedEvent.Balance balance : balances) {
            jdbcTemplate.update("""
                    INSERT INTO account_product_balances (
                        account_type, user_id, asset, available_units, locked_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, accountType.name(), userId, balance.asset(), balance.availableUnits(),
                    balance.lockedUnits(), Timestamp.from(projectedAt));
        }
    }

    private static void requireAccountType(AccountType accountType) {
        if (accountType == null || accountType == AccountType.FUNDING) {
            throw new IllegalArgumentException("产品账户类型不能为空或不能为 FUNDING");
        }
    }

    public record BalanceRow(AccountType accountType,
                             long userId,
                             String asset,
                             long availableUnits,
                             long lockedUnits,
                             Instant updatedAt) {
    }
}
