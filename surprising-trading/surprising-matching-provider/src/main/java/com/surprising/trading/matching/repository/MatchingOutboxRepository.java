package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.model.StoredOutboxRecord;
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
public class MatchingOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingSequenceRepository sequenceRepository;
    private final MatchingProperties properties;

    public MatchingOutboxRepository(JdbcTemplate jdbcTemplate, MatchingSequenceRepository sequenceRepository) {
        this(jdbcTemplate, sequenceRepository, new MatchingProperties());
    }

    @Autowired
    public MatchingOutboxRepository(JdbcTemplate jdbcTemplate,
                                    MatchingSequenceRepository sequenceRepository,
                                    MatchingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties == null ? new MatchingProperties() : properties;
    }

    public void enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
        requireCurrentProductTopic(aggregateType, topic);
        long id = sequenceRepository.nextSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, aggregateType, aggregateId, topic, eventKey, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        requireSingleRow(rows, "matching outbox enqueue");
    }

    public List<StoredOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')
                     ORDER BY topic, event_key, id
                ),
                candidates AS (
                    SELECT e.id
                  FROM trading_outbox_events e
                  JOIN earliest c ON c.id = e.id
                 WHERE e.published_at IS NULL
                   AND e.aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')
                """);
        appendTopicScope(sql, "e", args);
        sql.append("""
                   AND e.next_attempt_at <= ?
                   AND pg_try_advisory_xact_lock(hashtext(e.topic), hashtext(e.event_key))
                 ORDER BY e.topic, e.event_key, e.id
                     LIMIT ?
                     FOR UPDATE OF e SKIP LOCKED
                )
                UPDATE trading_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                  FROM candidates c
                 WHERE e.id = c.id
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                """);
        args.add(Timestamp.from(now));
        args.add(Math.max(1, limit));
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new StoredOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()),
                args.toArray());
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "matching outbox publish mark");
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Timestamp timestamp = Timestamp.from(now);
        int[] rows = jdbcTemplate.batchUpdate("""
                UPDATE trading_outbox_events
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
            throw new IllegalStateException("failed to mark matching outbox events published");
        }
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] != 1 && rows[i] != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to mark matching outbox event published " + ids.get(i));
            }
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        requireSingleRow(rows, "matching outbox failure mark");
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private void appendTopicScope(StringBuilder sql, String alias, List<Object> args) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND ").append(alias).append(".topic IN (?, ?, ?)\n");
        args.add(kafka.getMatchResultsTopic());
        args.add(kafka.getMatchTradesTopic());
        args.add(kafka.getOrderBookDepthTopic());
    }

    private void requireCurrentProductTopic(String aggregateType, String topic) {
        MatchingProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled() || !isMatchingAggregate(aggregateType)) {
            return;
        }
        String matchResultsTopic = kafka.getMatchResultsTopic();
        String matchTradesTopic = kafka.getMatchTradesTopic();
        String orderBookDepthTopic = kafka.getOrderBookDepthTopic();
        if (!matchResultsTopic.equals(topic) && !matchTradesTopic.equals(topic) && !orderBookDepthTopic.equals(topic)) {
            throw new IllegalStateException("matching outbox topic must match current product line: expected one of ["
                    + matchResultsTopic + ", " + matchTradesTopic + ", " + orderBookDepthTopic + "] actual=" + topic);
        }
    }

    private boolean isMatchingAggregate(String aggregateType) {
        return "MATCH_TRADE".equals(aggregateType)
                || "MATCH_RESULT".equals(aggregateType)
                || "ORDER_BOOK_DEPTH".equals(aggregateType);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
