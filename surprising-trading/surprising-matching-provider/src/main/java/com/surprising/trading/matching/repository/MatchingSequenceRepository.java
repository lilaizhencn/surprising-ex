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
        Long value = jdbcTemplate.queryForObject("SELECT nextval(CAST(? AS regclass))", Long.class,
                tradingSequenceIdentifier(sequenceName));
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

    private String tradingSequenceIdentifier(String sequenceName) {
        if (sequenceName == null || !sequenceName.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid trading sequence name: " + sequenceName);
        }
        return "public.trading_" + sequenceName.toLowerCase().replace('-', '_') + "_seq";
    }
}
