package com.surprising.price.index.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code price_symbol_sequences} 表。 */
@Repository
public class IndexPriceSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public IndexPriceSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String module, String symbol) {
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO price_symbol_sequences (module, symbol, sequence, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (module, symbol) DO UPDATE SET
                    sequence = price_symbol_sequences.sequence + 1,
                    updated_at = now()
                RETURNING sequence
                """, Long.class, module, symbol);
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate sequence for " + module + ":" + symbol);
        }
        return sequence;
    }
}
