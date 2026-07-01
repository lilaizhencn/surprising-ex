package com.surprising.risk.provider.repository;

import com.surprising.risk.provider.model.RiskOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskSequenceRepository sequenceRepository;

    public RiskOutboxRepository(JdbcTemplate jdbcTemplate, RiskSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
        long id = sequenceRepository.nextSequence("risk-outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO risk_outbox_events (
                    id, topic, event_key, event_type, payload,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, topic, eventKey, eventType, payload, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
        requireSingleRow(rows, "risk outbox enqueue");
    }

    public List<RiskOutboxRecord> lockPending(int limit) {
        return jdbcTemplate.query("""
                SELECT id, topic, event_key, payload::text AS payload
                  FROM risk_outbox_events
                 WHERE published_at IS NULL
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new RiskOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), limit);
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE risk_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "risk outbox publish mark");
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE risk_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "risk outbox failure mark");
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
