package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取兼容的 {@code account_deficits} 表。
 */
@Repository
public class AdlLegacyDeficitRepository implements AdlDeficitRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlLegacyDeficitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DeficitRow> claimResidual(String accountType, int batchSize, Duration minAge) {
        return jdbcTemplate.query("""
                SELECT ? AS account_type, user_id, asset,
                       deficit_units - reserved_units AS deficit_units
                  FROM account_deficits
                 WHERE deficit_units - reserved_units > 0
                   AND updated_at <= now() - (? * INTERVAL '1 millisecond')
                 ORDER BY updated_at ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new DeficitRow(
                rs.getString("account_type"), rs.getLong("user_id"), rs.getString("asset"),
                rs.getLong("deficit_units")), accountType, minAge.toMillis(), batchSize);
    }

    @Override
    public Map<Long, Long> remainingByUsers(ProductLine productLine, String asset, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(asset);
        args.addAll(userIds);
        Map<Long, Long> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT user_id, deficit_units - reserved_units AS remaining_units
                  FROM account_deficits
                 WHERE asset = ? AND user_id IN (%s)
                """.formatted(placeholders), rs -> {
            while (rs.next()) {
                result.put(rs.getLong("user_id"), rs.getLong("remaining_units"));
            }
            return null;
        }, args.toArray());
        return Map.copyOf(result);
    }
}
