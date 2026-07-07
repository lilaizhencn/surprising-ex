package com.surprising.risk.provider.repository;

import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.RiskOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RiskOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskSequenceRepository sequenceRepository;
    private final RiskProperties properties;

    public RiskOutboxRepository(JdbcTemplate jdbcTemplate, RiskSequenceRepository sequenceRepository) {
        this(jdbcTemplate, sequenceRepository, new RiskProperties());
    }

    @Autowired
    public RiskOutboxRepository(JdbcTemplate jdbcTemplate,
                                RiskSequenceRepository sequenceRepository,
                                RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
        requireCurrentProductTopic(topic);
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
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT id, topic, event_key, payload::text AS payload
                  FROM risk_outbox_events
                 WHERE published_at IS NULL
                """);
        appendTopicScope(sql, args);
        sql.append("""
                   AND next_attempt_at <= now()
                 ORDER BY next_attempt_at ASC, id ASC
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RiskOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload")), args.toArray());
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

    private void appendTopicScope(StringBuilder sql, List<Object> args) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND topic IN (?, ?, ?)\n");
        args.add(kafka.getAccountRiskEventsTopic());
        args.add(kafka.getPositionRiskEventsTopic());
        args.add(kafka.getLiquidationCandidatesTopic());
    }

    private void requireCurrentProductTopic(String topic) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String accountRiskEventsTopic = kafka.getAccountRiskEventsTopic();
        String positionRiskEventsTopic = kafka.getPositionRiskEventsTopic();
        String liquidationCandidatesTopic = kafka.getLiquidationCandidatesTopic();
        if (!accountRiskEventsTopic.equals(topic)
                && !positionRiskEventsTopic.equals(topic)
                && !liquidationCandidatesTopic.equals(topic)) {
            throw new IllegalStateException("risk outbox topic must match current product line: expected one of ["
                    + accountRiskEventsTopic + ", " + positionRiskEventsTopic + ", "
                    + liquidationCandidatesTopic + "] actual=" + topic);
        }
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
