package com.surprising.instrument.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_symbol_sequences} 表。 */
@Repository
public class InstrumentSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String symbol, long initialVersion) {
        Long version = jdbcTemplate.queryForObject("""
                INSERT INTO instrument_symbol_sequences (symbol, version, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (symbol) DO UPDATE SET
                    version = GREATEST(instrument_symbol_sequences.version + 1, EXCLUDED.version),
                    updated_at = now()
                RETURNING version
                """, Long.class, symbol, initialVersion);
        if (version == null) {
            throw new IllegalStateException("Failed to allocate instrument version for " + symbol);
        }
        return version;
    }
}
