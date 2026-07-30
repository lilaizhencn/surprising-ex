package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ProductBalanceRow> find(long userId, AccountType accountType, String asset) {
        return jdbcTemplate.query("""
                SELECT user_id, account_type, asset, available_units, locked_units, updated_at
                  FROM account_product_balances
                 WHERE user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> toRow(rs), userId, accountType.name(), asset)
                .stream().findFirst();
    }

    public List<ProductBalanceRow> findByUser(long userId, AccountType accountType) {
        StringBuilder sql = new StringBuilder("""
                SELECT user_id, account_type, asset, available_units, locked_units, updated_at
                  FROM account_product_balances
                 WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (accountType != null) {
            sql.append("   AND account_type = ?\n");
            args.add(accountType.name());
        }
        sql.append(" ORDER BY account_type ASC, asset ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toRow(rs), args.toArray());
    }

    private static ProductBalanceRow toRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ProductBalanceRow(
                resultSet.getLong("user_id"),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getString("asset"),
                resultSet.getLong("available_units"),
                resultSet.getLong("locked_units"),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    public record ProductBalanceRow(
            long userId,
            AccountType accountType,
            String asset,
            long availableUnits,
            long lockedUnits,
            Instant updatedAt) {
    }
}
