package com.surprising.account.provider.repository;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountDeficitRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountDeficitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OptionalLong findUnits(long userId, String asset) {
        List<Long> rows = jdbcTemplate.query("""
                SELECT deficit_units
                  FROM account_deficits
                 WHERE user_id = ?
                   AND asset = ?
                """, (rs, rowNum) -> rs.getLong("deficit_units"), userId, asset);
        return rows.isEmpty() ? OptionalLong.empty() : OptionalLong.of(rows.getFirst());
    }

    public List<DeficitRow> findByUser(long userId) {
        return jdbcTemplate.query("""
                SELECT asset, deficit_units
                  FROM account_deficits
                 WHERE user_id = ?
                """, (rs, rowNum) -> new DeficitRow(
                        rs.getString("asset"),
                        rs.getLong("deficit_units")), userId);
    }

    public record DeficitRow(String asset, long deficitUnits) {
    }
}
