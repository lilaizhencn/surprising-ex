package com.surprising.risk.provider.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RiskSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public RiskSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> nextSequences(String sequenceName, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("sequence count must be non-negative");
        }
        if (count == 0) {
            return List.of();
        }
        String databaseSequence = switch (sequenceName) {
            case "risk-snapshot" -> "risk_snapshot_id_seq";
            case "risk-event" -> "risk_event_id_seq";
            case "liquidation-candidate" -> "risk_liquidation_candidate_id_seq";
            case "risk-outbox" -> "risk_outbox_id_seq";
            default -> throw new IllegalArgumentException("Unknown risk sequence " + sequenceName);
        };
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT nextval(?::regclass) FROM generate_series(1, ?)", Long.class, databaseSequence, count);
        if (values.size() != count) {
            throw new IllegalStateException("Failed to allocate " + count + " values from " + sequenceName);
        }
        return values;
    }
}
