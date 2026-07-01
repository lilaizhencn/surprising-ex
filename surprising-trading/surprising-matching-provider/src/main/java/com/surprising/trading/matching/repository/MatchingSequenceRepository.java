package com.surprising.trading.matching.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public MatchingSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO trading_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = trading_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("Failed to allocate sequence " + sequenceName);
        }
        return value;
    }

    public int nextIntSequence(String sequenceName) {
        long value = nextSequence(sequenceName);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException("sequence overflow for " + sequenceName);
        }
        return (int) value;
    }
}
