package com.surprising.funding.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 price_symbol_sequences 表。
 */
@Repository
public class FundingSequenceRepository {

    private static final String RATE_MODULE = "funding-rate";

    private final JdbcTemplate jdbcTemplate;

    public FundingSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String symbol) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO price_symbol_sequences (module, symbol, sequence, updated_at)
                VALUES (?, ?, 1, now())
                ON CONFLICT (module, symbol) DO UPDATE SET
                    sequence = price_symbol_sequences.sequence + 1,
                    updated_at = now()
                RETURNING sequence
                """, Long.class, RATE_MODULE, symbol);
        if (value == null) {
            throw new IllegalStateException("failed to allocate funding-rate sequence for " + symbol);
        }
        return value;
    }
}
