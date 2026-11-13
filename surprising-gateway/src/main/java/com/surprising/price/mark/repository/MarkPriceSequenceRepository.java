package com.surprising.price.mark.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_symbol_sequences} 表。 */
@Repository
public class MarkPriceSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarkPriceSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String module, String instrumentId) {
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO price_symbol_sequences (module, instrument_id, sequence, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (module, instrument_id) DO UPDATE SET
                    sequence = price_symbol_sequences.sequence + 1,
                    updated_at = now()
                RETURNING sequence
                """, Long.class, module, instrumentId);
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate sequence for " + module + ":" + instrumentId);
        }
        return sequence;
    }
}
