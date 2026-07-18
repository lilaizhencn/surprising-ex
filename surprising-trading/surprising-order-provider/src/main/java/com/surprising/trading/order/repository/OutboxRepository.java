package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final OrderRepository orderRepository;
    private final TradingOrderProperties properties;

    public OutboxRepository(JdbcTemplate jdbcTemplate, OrderRepository orderRepository) {
        this(jdbcTemplate, orderRepository, new TradingOrderProperties());
    }

    @Autowired
    public OutboxRepository(JdbcTemplate jdbcTemplate,
                            OrderRepository orderRepository,
                            TradingOrderProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderRepository = orderRepository;
        this.properties = properties == null ? new TradingOrderProperties() : properties;
    }

    public long enqueue(String aggregateType,
                        long aggregateId,
                        String topic,
                        String eventKey,
                        String eventType,
                        String payload,
                        Instant now) {
        requireCurrentProductTopic(aggregateType, topic);
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

    public List<OutboxRecord> claimPending(int limit, Instant leaseUntil, Instant now) {
        // The earliest unpublished row still blocks later rows for the same topic+key, preserving stream order.
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH earliest AS MATERIALIZED (
                    SELECT DISTINCT ON (topic, event_key)
                           id
                      FROM trading_outbox_events
                     WHERE published_at IS NULL
                       AND aggregate_type = 'ORDER'
                     ORDER BY topic, event_key, id
                ),
                candidates AS MATERIALIZED (
                    SELECT e.id
                      FROM trading_outbox_events e
                     WHERE e.published_at IS NULL
                       AND e.aggregate_type = 'ORDER'
                       AND e.id = ANY (ARRAY(SELECT id FROM earliest))
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
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OutboxRecord(
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
        if (rows != 1) {
            throw new IllegalStateException("failed to mark trading outbox event published " + id);
        }
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
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to mark all trading outbox events published: expected="
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
                 WHERE id = ?
                """, truncate(error), Timestamp.from(now), Timestamp.from(now), id);
        if (rows != 1) {
            throw new IllegalStateException("failed to mark trading outbox event failed " + id);
        }
    }

    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                      FROM trading_outbox_events
                     WHERE aggregate_type = 'ORDER'
                       AND published_at < ?
                     ORDER BY published_at ASC, id ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM trading_outbox_events e
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

    private void appendTopicScope(StringBuilder sql, String alias, List<Object> args) {
        TradingOrderProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled()) {
            return;
        }
        sql.append("   AND ").append(alias).append(".topic IN (?, ?, ?)\n");
        args.add(kafka.getOrderEventsTopic());
        args.add(kafka.getOrderCommandsTopic());
        args.add(kafka.getAccountUserCommandsTopic());
    }

    private void requireCurrentProductTopic(String aggregateType, String topic) {
        TradingOrderProperties.Kafka kafka = properties.getKafka();
        if (!kafka.isProductTopicsEnabled() || !"ORDER".equals(aggregateType)) {
            return;
        }
        String orderEventsTopic = kafka.getOrderEventsTopic();
        String orderCommandsTopic = kafka.getOrderCommandsTopic();
        String accountUserCommandsTopic = kafka.getAccountUserCommandsTopic();
        if (!orderEventsTopic.equals(topic)
                && !orderCommandsTopic.equals(topic)
                && !accountUserCommandsTopic.equals(topic)) {
            throw new IllegalStateException("trading outbox topic must match current product line: expected one of ["
                    + orderEventsTopic + ", " + orderCommandsTopic + ", " + accountUserCommandsTopic
                    + "] actual=" + topic);
        }
    }
}
