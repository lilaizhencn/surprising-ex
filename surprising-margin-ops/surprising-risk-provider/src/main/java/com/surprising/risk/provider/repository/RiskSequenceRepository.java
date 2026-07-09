package com.surprising.risk.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO risk_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = risk_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("Failed to allocate risk sequence " + sequenceName);
        }
        return value;
    }
}
