package com.surprising.trading.trigger.repository;

import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import com.surprising.trading.trigger.model.TriggerOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Transactional outbox for user-visible trigger-order status snapshots. */
@Repository
public class TriggerOrderOutboxRepository {

    private static final String AGGREGATE_TYPE = "TRIGGER_ORDER";

    private final JdbcTemplate jdbcTemplate;
    private final TriggerOrderRepository triggerOrderRepository;
    private final TriggerProperties properties;
    private final ObjectMapper objectMapper;

    public TriggerOrderOutboxRepository(JdbcTemplate jdbcTemplate,
                                        TriggerOrderRepository triggerOrderRepository,
                                        TriggerProperties properties,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.triggerOrderRepository = triggerOrderRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public TriggerOrderUpdatedEvent enqueue(TriggerOrderRecord order, TriggerOrderResponse response) {
        long eventId = triggerOrderRepository.nextSequence("event");
        TriggerOrderUpdatedEvent event = new TriggerOrderUpdatedEvent(
                eventId, order.productLine(), response, order.updatedAt(), order.traceId());
        long outboxId = triggerOrderRepository.nextSequence("outbox");
        int rows = jdbcTemplate.update("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, outboxId, AGGREGATE_TYPE, order.triggerOrderId(),
                properties.getKafka().getTriggerOrderEventsTopic(), order.symbol(), order.status().name(),
                payload(event), Timestamp.from(order.updatedAt()), Timestamp.from(order.updatedAt()),
                Timestamp.from(order.updatedAt()));
        requireSingleRow(rows, "trigger order outbox enqueue");
        return event;
    }

    public List<TriggerOutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        return jdbcTemplate.query("""
                WITH earliest AS (
                    SELECT DISTINCT ON (topic, event_key) id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type = ?
                       AND topic = ?
                     ORDER BY topic, event_key, id
                ),
                candidates AS (
                    SELECT e.id
                      FROM trading_outbox_events e
                      JOIN earliest first_event ON first_event.id = e.id
                     WHERE e.published_at IS NULL
                       AND e.aggregate_type = ?
                       AND e.topic = ?
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
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload
                """, (rs, rowNum) -> new TriggerOutboxRecord(
                        rs.getLong("id"), rs.getString("topic"), rs.getString("event_key"),
                        rs.getString("payload")),
                AGGREGATE_TYPE, properties.getKafka().getTriggerOrderEventsTopic(),
                AGGREGATE_TYPE, properties.getKafka().getTriggerOrderEventsTopic(), Timestamp.from(now),
                Math.max(1, limit), Timestamp.from(leaseUntil), Timestamp.from(now));
    }

    public void markPublished(long id, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET published_at = ?, updated_at = ?, last_error = NULL
                 WHERE id = ? AND aggregate_type = ?
                """, Timestamp.from(now), Timestamp.from(now), id, AGGREGATE_TYPE);
        requireSingleRow(rows, "trigger order outbox publish mark");
    }

    public void markPublished(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        Timestamp timestamp = Timestamp.from(now);
        String placeholders = String.join(", ", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                UPDATE trading_outbox_events
                   SET published_at = ?,
                       updated_at = ?,
                       last_error = NULL
                 WHERE published_at IS NULL
                   AND aggregate_type = ?
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 3);
        args.add(timestamp);
        args.add(timestamp);
        args.add(AGGREGATE_TYPE);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to mark all trigger order outbox events published: expected="
                    + uniqueIds.size() + " actual=" + rows);
        }
    }

    public void markFailed(long id, String error, Instant now) {
        int rows = jdbcTemplate.update("""
                UPDATE trading_outbox_events
                   SET attempts = attempts + 1,
                       last_error = ?,
                       next_attempt_at = ? + (CAST(power(2, LEAST(attempts, 6)) AS INTEGER) * INTERVAL '1 second'),
                       updated_at = ?
                 WHERE id = ? AND aggregate_type = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id, AGGREGATE_TYPE);
        requireSingleRow(rows, "trigger order outbox failure mark");
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM trading_outbox_events
                     WHERE aggregate_type = ?
                       AND published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM trading_outbox_events e
                 USING candidates c
                 WHERE e.id = c.id
                """, AGGREGATE_TYPE, Timestamp.from(cutoff), Math.max(1, limit));
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize trigger order event", ex);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }
}
