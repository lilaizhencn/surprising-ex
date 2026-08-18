package com.surprising.insurance.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 insurance_sequences 表。
 */
@Repository
public class InsuranceSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public InsuranceSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO insurance_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = insurance_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("failed to allocate insurance sequence " + sequenceName);
        }
        return value;
    }
}
