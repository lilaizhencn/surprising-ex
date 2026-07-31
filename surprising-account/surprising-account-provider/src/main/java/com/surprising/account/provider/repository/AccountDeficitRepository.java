package com.surprising.account.provider.repository;

import java.sql.Timestamp;
import java.time.Instant;
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

    public long lockUnits(long userId, String asset) {
        return jdbcTemplate.query("""
                SELECT deficit_units
                  FROM account_deficits
                 WHERE user_id = ?
                   AND asset = ?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getLong("deficit_units"), userId, asset)
                .stream().findFirst().orElse(0L);
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

    public boolean reserve(long userId, String asset, long amountUnits, Instant now) {
        return !jdbcTemplate.query("""
                UPDATE account_deficits
                   SET reserved_units = reserved_units + ?, updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND deficit_units - reserved_units >= ?
             RETURNING deficit_units - reserved_units
                """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                userId, asset, amountUnits).isEmpty();
    }

    public OptionalLong finalizeReservation(long userId, String asset, long amountUnits, Instant now) {
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_deficits
                   SET deficit_units = deficit_units - ?,
                       reserved_units = reserved_units - ?,
                       updated_at = ?
                 WHERE user_id = ? AND asset = ?
                   AND deficit_units >= ? AND reserved_units >= ?
             RETURNING deficit_units
                """, (rs, rowNum) -> rs.getLong(1), amountUnits, amountUnits, Timestamp.from(now),
                userId, asset, amountUnits, amountUnits);
        return rows.size() == 1 ? OptionalLong.of(rows.getFirst()) : OptionalLong.empty();
    }

    public OptionalLong releaseReservation(long userId, String asset, long amountUnits, Instant now) {
        List<Long> rows = jdbcTemplate.query("""
                UPDATE account_deficits
                   SET reserved_units = reserved_units - ?, updated_at = ?
                 WHERE user_id = ? AND asset = ? AND reserved_units >= ?
             RETURNING deficit_units - reserved_units
                """, (rs, rowNum) -> rs.getLong(1), amountUnits, Timestamp.from(now),
                userId, asset, amountUnits);
        return rows.size() == 1 ? OptionalLong.of(rows.getFirst()) : OptionalLong.empty();
    }

    public record DeficitRow(String asset, long deficitUnits) {
    }
}
