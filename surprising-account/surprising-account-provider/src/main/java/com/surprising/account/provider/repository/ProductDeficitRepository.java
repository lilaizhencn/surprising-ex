package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductDeficitRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProductDeficitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptionalLong findUnits(long userId, AccountType accountType, String asset) {
        List<Long> rows = jdbcTemplate.query("""
                SELECT deficit_units
                  FROM account_product_deficits
                 WHERE user_id = ?
                   AND account_type = ?
                   AND asset = ?
                """, (rs, rowNum) -> rs.getLong("deficit_units"), userId, accountType.name(), asset);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.getFirst());
    }

    public List<ProductDeficitRow> findByUser(long userId, AccountType accountType) {
        StringBuilder sql = new StringBuilder("""
                SELECT account_type, asset, deficit_units
                  FROM account_product_deficits
                 WHERE user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (accountType != null) {
            sql.append("   AND account_type = ?\n");
            args.add(accountType.name());
        }
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ProductDeficitRow(
                AccountType.valueOf(rs.getString("account_type")),
                rs.getString("asset"),
                rs.getLong("deficit_units")), args.toArray());
    }

    public record ProductDeficitRow(AccountType accountType, String asset, long deficitUnits) {
    }
}
