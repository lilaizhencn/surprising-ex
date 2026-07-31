package com.surprising.trading.trigger.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code trading_trigger_instrument_lifecycle_fences} 表。 */
@Repository
public class TriggerInstrumentLifecycleFenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public TriggerInstrumentLifecycleFenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockForPlacement(ProductLine productLine, String symbol, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO trading_trigger_instrument_lifecycle_fences (
                    product_line, symbol, instrument_version, blocked, updated_at
                ) VALUES (?, ?, 0, FALSE, ?)
                ON CONFLICT (product_line, symbol) DO NOTHING
                """, productLine.name(), symbol, Timestamp.from(now));
        Boolean blocked = jdbcTemplate.queryForObject("""
                SELECT blocked
                  FROM trading_trigger_instrument_lifecycle_fences
                 WHERE product_line = ?
                   AND symbol = ?
                   FOR UPDATE
                """, Boolean.class, productLine.name(), symbol);
        if (Boolean.TRUE.equals(blocked)) {
            throw new IllegalStateException("instrument is settling");
        }
    }

    public void blockForSettlement(
            ProductLine productLine, String symbol, long instrumentVersion, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO trading_trigger_instrument_lifecycle_fences (
                    product_line, symbol, instrument_version, blocked, updated_at
                ) VALUES (?, ?, ?, TRUE, ?)
                ON CONFLICT (product_line, symbol) DO UPDATE SET
                    instrument_version = EXCLUDED.instrument_version,
                    blocked = TRUE,
                    updated_at = EXCLUDED.updated_at
                WHERE EXCLUDED.instrument_version >=
                      trading_trigger_instrument_lifecycle_fences.instrument_version
                """, productLine.name(), symbol, instrumentVersion, Timestamp.from(now));
    }
}
