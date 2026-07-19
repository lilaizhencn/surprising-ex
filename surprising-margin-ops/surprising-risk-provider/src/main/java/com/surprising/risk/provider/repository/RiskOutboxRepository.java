package com.surprising.risk.provider.repository;

import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.RiskOutboxRecord;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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

    public void enqueue(List<PendingRiskOutboxEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (PendingRiskOutboxEvent event : events) {
            requireCurrentProductTopic(event.topic());
        }
        List<Long> ids = sequenceRepository.nextSequences("risk-outbox", events.size());
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_outbox_events (
                    id, topic, event_key, event_type, payload,
                    next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                PendingRiskOutboxEvent event = events.get(i);
                Timestamp timestamp = Timestamp.from(event.eventTime());
                ps.setLong(1, ids.get(i));
                ps.setString(2, event.topic());
                ps.setString(3, event.eventKey());
                ps.setString(4, event.eventType());
                ps.setString(5, event.payload());
                ps.setTimestamp(6, timestamp);
                ps.setTimestamp(7, timestamp);
                ps.setTimestamp(8, timestamp);
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
        requireBatch(rows, events.size(), "risk outbox enqueue");
    }

    public List<RiskOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        int effectiveLimit = Math.max(1, limit);
        int perKeyLimit = Math.max(1, Math.min(properties.getOutbox().getMaxRowsPerKey(), effectiveLimit));
        int lockedKeyLimit = lockedKeyLimit(effectiveLimit, perKeyLimit);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           topic, event_key, id AS first_id, next_attempt_at
                      FROM risk_outbox_events
                     WHERE published_at IS NULL
                """);
        appendTopicScope(sql, args);
        sql.append("""
                     ORDER BY topic, event_key, id
                ),
                locked_keys AS (
                    SELECT topic, event_key, first_id
                      FROM earliest
                     WHERE next_attempt_at <= ?
                       AND pg_try_advisory_xact_lock(hashtext(topic), hashtext(event_key))
                     ORDER BY first_id
                     LIMIT ?
                ),
                candidates AS (
                    SELECT due_prefix.id
                      FROM locked_keys k
                      CROSS JOIN LATERAL (
                          SELECT ranked.id, ranked.key_rank
                            FROM (
                                SELECT prefix.id,
                                       prefix.next_attempt_at,
                                       row_number() OVER (ORDER BY prefix.id) AS key_rank,
                                       bool_or(prefix.next_attempt_at > ?) OVER (
                                           ORDER BY prefix.id
                                           ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                                       ) AS blocked_by_retry
                                  FROM (
                                      SELECT e.id, e.next_attempt_at
                                        FROM risk_outbox_events e
                                       WHERE e.topic = k.topic
                                         AND e.event_key = k.event_key
                                         AND e.published_at IS NULL
                                       ORDER BY e.id
                                       LIMIT ?
                                  ) prefix
                            ) ranked
                           WHERE ranked.next_attempt_at <= ?
                             AND NOT ranked.blocked_by_retry
                           ORDER BY ranked.id
                      ) due_prefix
                     ORDER BY due_prefix.key_rank, k.first_id, due_prefix.id
                     LIMIT ?
                """);
        sql.append("""
                )
                UPDATE risk_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                  FROM candidates c
                 WHERE e.id = c.id
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                """);
        args.add(Timestamp.from(now));
        args.add(lockedKeyLimit);
        args.add(Timestamp.from(now));
        args.add(perKeyLimit);
        args.add(Timestamp.from(now));
        args.add(effectiveLimit);
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new RiskOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()), args.toArray());
    }

    private int lockedKeyLimit(int limit, int perKeyLimit) {
        int groupsToFillBatch = (limit + perKeyLimit - 1) / perKeyLimit;
        int concurrencyWindow = Math.max(1, properties.getOutbox().getMaxInFlight()) * 4;
        return Math.max(1, Math.min(limit, Math.max(groupsToFillBatch, concurrencyWindow)));
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

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Timestamp timestamp = Timestamp.from(now);
        int[] rows = jdbcTemplate.batchUpdate("""
                UPDATE risk_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setTimestamp(1, timestamp);
                ps.setTimestamp(2, timestamp);
                ps.setLong(3, ids.get(i));
            }

            @Override
            public int getBatchSize() {
                return ids.size();
            }
        });
        if (rows.length != ids.size()) {
            throw new IllegalStateException("failed to mark risk outbox events published");
        }
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] != 1 && rows[i] != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to mark risk outbox event published " + ids.get(i));
            }
        }
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

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM risk_outbox_events
                     WHERE published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM risk_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, List<Object> args) {
        appendTopicScope(sql, "", args);
    }

    private void appendTopicScope(StringBuilder sql, String alias, List<Object> args) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
        sql.append("   AND ").append(prefix).append("topic = ?\n");
        args.add(kafka.getLiquidationCandidatesTopic());
    }

    private void requireCurrentProductTopic(String topic) {
        RiskProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        String liquidationCandidatesTopic = kafka.getLiquidationCandidatesTopic();
        if (!liquidationCandidatesTopic.equals(topic)) {
            throw new IllegalStateException("risk outbox topic must match current product line: expected "
                    + liquidationCandidatesTopic + " actual=" + topic);
        }
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private void requireBatch(int[] rows, int expectedSize, String operation) {
        if (rows.length != expectedSize) {
            throw new IllegalStateException("failed to write " + operation + " batch");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record PendingRiskOutboxEvent(String topic,
                                         String eventKey,
                                         String eventType,
                                         String payload,
                                         Instant eventTime) {
    }
}
