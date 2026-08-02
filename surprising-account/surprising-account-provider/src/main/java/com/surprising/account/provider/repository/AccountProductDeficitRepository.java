package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 产品账户亏空的异步完整快照投影仓储。 */
@Repository
public class AccountProductDeficitRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountProductDeficitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DeficitRow> findByUser(AccountType accountType, long userId) {
        requireAccountType(accountType);
        return jdbcTemplate.query("""
                SELECT account_type, user_id, asset, deficit_units, reserved_units, updated_at
                  FROM account_product_deficits
                 WHERE account_type = ? AND user_id = ?
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new DeficitRow(
                AccountType.valueOf(rs.getString("account_type")),
                rs.getLong("user_id"),
                rs.getString("asset"),
                rs.getLong("deficit_units"),
                rs.getLong("reserved_units"),
                rs.getTimestamp("updated_at").toInstant()), accountType.name(), userId);
    }

    /** 只由异步完整快照投影调用，按产品账户原子替换亏空列表。 */
    public void replaceProjection(AccountType accountType,
                                  long userId,
                                  List<PerpetualAccountStateUpdatedEvent.Deficit> deficits,
                                  Instant projectedAt) {
        requireAccountType(accountType);
        jdbcTemplate.update("""
                DELETE FROM account_product_deficits
                 WHERE account_type = ? AND user_id = ?
                """, accountType.name(), userId);
        if (deficits == null) {
            return;
        }
        for (PerpetualAccountStateUpdatedEvent.Deficit deficit : deficits) {
            jdbcTemplate.update("""
                    INSERT INTO account_product_deficits (
                        account_type, user_id, asset, deficit_units, reserved_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, accountType.name(), userId, deficit.asset(), deficit.deficitUnits(),
                    deficit.reservedUnits(), Timestamp.from(projectedAt));
        }
    }

    private static void requireAccountType(AccountType accountType) {
        if (accountType == null || accountType == AccountType.FUNDING) {
            throw new IllegalArgumentException("产品账户类型不能为空或不能为 FUNDING");
        }
    }

    public record DeficitRow(AccountType accountType,
                             long userId,
                             String asset,
                             long deficitUnits,
                             long reservedUnits,
                             Instant updatedAt) {
    }
}
