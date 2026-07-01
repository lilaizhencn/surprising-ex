package com.surprising.liquidation.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LiquidationSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextTradingSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO trading_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = trading_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("Failed to allocate trading sequence " + sequenceName);
        }
        return value;
    }

    public long nextLiquidationSequence(String sequenceName) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO liquidation_sequences (sequence_name, sequence_value, updated_at)
                VALUES (?, 1, now())
                ON CONFLICT (sequence_name) DO UPDATE SET
                    sequence_value = liquidation_sequences.sequence_value + 1,
                    updated_at = now()
                RETURNING sequence_value
                """, Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("Failed to allocate liquidation sequence " + sequenceName);
        }
        return value;
    }
}
