package com.surprising.trading.matching.repository;

import com.surprising.trading.matching.model.StoredOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchingOutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingSequenceRepository sequenceRepository;

    public MatchingOutboxRepository(JdbcTemplate jdbcTemplate, MatchingSequenceRepository sequenceRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.sequenceRepository = sequenceRepository;
    }

    public void enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
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
        return jdbcTemplate.query("""
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
                """, (rs, rowNum) -> new StoredOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()),
                Timestamp.from(now), Math.max(1, limit), Timestamp.from(leaseUntil), Timestamp.from(now));
    }

    public List<StoredOutboxRecord> lockPending(int limit) {
        return jdbcTemplate.query("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')
                     ORDER BY topic, event_key, id
                )
                SELECT e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                  FROM trading_outbox_events e
                  JOIN earliest c ON c.id = e.id
                 WHERE e.published_at IS NULL
                   AND e.aggregate_type IN ('MATCH_TRADE', 'MATCH_RESULT', 'ORDER_BOOK_DEPTH')
                   AND e.next_attempt_at <= now()
                   AND pg_try_advisory_xact_lock(hashtext(e.topic), hashtext(e.event_key))
                 ORDER BY e.topic, e.event_key, e.id
                 LIMIT ?
                 FOR UPDATE OF e SKIP LOCKED
                """, (rs, rowNum) -> new StoredOutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()), limit);
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

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
