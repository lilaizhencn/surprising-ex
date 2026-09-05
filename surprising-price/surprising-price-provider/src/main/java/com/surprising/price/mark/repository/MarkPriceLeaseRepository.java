package com.surprising.price.mark.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_symbol_leases} 表。 */
@Repository
public class MarkPriceLeaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarkPriceLeaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean acquire(String module, String symbol, String ownerId, Duration leaseDuration) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(leaseDuration);
        List<Boolean> rows = jdbcTemplate.query("""
                INSERT INTO price_symbol_leases (module, symbol, owner_id, lease_until, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (module, symbol) DO UPDATE SET
                    owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    updated_at = EXCLUDED.updated_at
                WHERE price_symbol_leases.owner_id = EXCLUDED.owner_id
                   OR price_symbol_leases.lease_until <= EXCLUDED.updated_at
                RETURNING TRUE
                """, (rs, rowNum) -> rs.getBoolean(1),
                module, symbol, ownerId, Timestamp.from(leaseUntil), Timestamp.from(now));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }
}
