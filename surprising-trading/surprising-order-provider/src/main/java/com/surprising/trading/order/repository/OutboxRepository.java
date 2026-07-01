package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.OutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;

    public OutboxRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
    }

    public long enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
        long outboxId = orderRepository.nextSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, aggregateType, aggregateId, topic, eventKey, eventType, payload,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (rows != 1) {
            throw new IllegalStateException("failed to enqueue trading outbox event " + outboxId);
        }
        return outboxId;
    }

    public List<OutboxRecord> lockPending(int limit) {
        // Advisory locks keep every topic+symbol stream ordered when several provider nodes drain the table.
        String sql = """
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type = 'ORDER'
                     ORDER BY topic, event_key, id
                )
                SELECT e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                  FROM trading_outbox_events e
                  JOIN earliest c ON c.id = e.id
                 WHERE e.published_at IS NULL
                   AND e.aggregate_type = 'ORDER'
                   AND e.next_attempt_at <= now()
                   AND pg_try_advisory_xact_lock(hashtext(e.topic), hashtext(e.event_key))
                 ORDER BY e.topic, e.event_key, e.id
                 LIMIT ?
                 FOR UPDATE OF e SKIP LOCKED
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new OutboxRecord(
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
        if (rows != 1) {
            throw new IllegalStateException("failed to mark trading outbox event published " + id);
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
        if (rows != 1) {
            throw new IllegalStateException("failed to mark trading outbox event failed " + id);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
