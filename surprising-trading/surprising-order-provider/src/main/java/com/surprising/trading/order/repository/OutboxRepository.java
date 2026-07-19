package com.surprising.trading.order.repository;

import com.surprising.trading.order.model.OutboxRecord;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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

    public void enqueueBatch(List<OrderOutboxWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        for (OrderOutboxWrite write : writes) {
            if (write == null || write.createdAt() == null) {
                throw new IllegalArgumentException("order outbox write and createdAt are required");
            }
            requireCurrentProductTopic(write.aggregateType(), write.topic());
        }
        List<Long> ids = jdbcTemplate.query("""
                SELECT nextval('trading_outbox_seq') AS id
                  FROM generate_series(1, ?) AS n
                 ORDER BY n
                """, (rs, rowNum) -> rs.getLong("id"), writes.size());
        if (ids.size() != writes.size()) {
            throw new IllegalStateException("failed to allocate order outbox ids");
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO trading_outbox_events (
                    id, aggregate_type, aggregate_id, topic, event_key, event_type,
                    payload, next_attempt_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
                OrderOutboxWrite write = writes.get(index);
                Timestamp createdAt = Timestamp.from(write.createdAt());
                statement.setLong(1, ids.get(index));
                statement.setString(2, write.aggregateType());
                statement.setLong(3, write.aggregateId());
                statement.setString(4, write.topic());
                statement.setString(5, write.eventKey());
                statement.setString(6, write.eventType());
                statement.setString(7, write.payload());
                statement.setTimestamp(8, createdAt);
                statement.setTimestamp(9, createdAt);
                statement.setTimestamp(10, createdAt);
            }

            @Override
            public int getBatchSize() {
                return writes.size();
            }
        });
        requireCompleteBatch(rows, writes.size(), "order outbox enqueue");
    }

    public List<OutboxStreamBatch> claimPendingBatches(int limit, Instant leaseUntil, Instant now) {
        int effectiveLimit = Math.max(1, limit);
        int perKeyLimit = Math.max(1, Math.min(properties.getOutbox().getMaxRowsPerKey(), effectiveLimit));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                WITH pending_ranked AS MATERIALIZED (
                    SELECT event.id,
                           event.xmin::text::bigint AS row_version,
                           event.aggregate_type,
                           event.topic,
                           event.event_key,
                           event.event_type,
                           event.next_attempt_at,
                           row_number() OVER (
                               PARTITION BY event.aggregate_type, event.topic, event.event_key
                               ORDER BY event.id
                           ) AS key_rank,
                           first_value(event.next_attempt_at) OVER (
                               PARTITION BY event.aggregate_type, event.topic, event.event_key
                               ORDER BY event.id
                           ) AS head_next_attempt_at,
                           first_value(event.event_type) OVER (
                               PARTITION BY event.aggregate_type, event.topic, event.event_key
                               ORDER BY event.id
                           ) AS head_event_type
                      FROM trading_outbox_events event
                     WHERE event.published_at IS NULL
                       AND event.aggregate_type = 'ORDER'
                """);
        appendTopicScope(sql, "event", args);
        sql.append("""
                ),
                candidates AS MATERIALIZED (
                    SELECT id, row_version
                      FROM pending_ranked
                     WHERE key_rank <= ?
                       AND head_next_attempt_at <= ?
                       AND next_attempt_at <= ?
                     ORDER BY CASE
                                  WHEN head_event_type IN ('ORDER_RESERVE', 'PLACE', 'CANCEL') THEN 0
                                  ELSE 1
                              END,
                              next_attempt_at,
                              id
                     LIMIT ?
                )
                UPDATE trading_outbox_events e
                   SET next_attempt_at = ?,
                       updated_at = ?
                 WHERE (e.id, e.xmin::text::bigint) IN (
                           SELECT id, row_version FROM candidates
                       )
             RETURNING e.id, e.topic, e.event_key, e.payload::text AS payload, e.next_attempt_at
                """);
        args.add(perKeyLimit);
        args.add(Timestamp.from(now));
        args.add(Timestamp.from(now));
        args.add(effectiveLimit);
        args.add(Timestamp.from(leaseUntil));
        args.add(Timestamp.from(now));
        List<OutboxRecord> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new OutboxRecord(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getString("event_key"),
                rs.getString("payload"),
                rs.getTimestamp("next_attempt_at").toInstant()),
                args.toArray());
        return toStreamBatches(rows);
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

    public void releasePending(List<Long> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> uniqueIds = ids.stream().distinct().toList();
        Timestamp timestamp = Timestamp.from(now);
        String placeholders = String.join(", ", Collections.nCopies(uniqueIds.size(), "?"));
        String sql = """
                UPDATE trading_outbox_events
                   SET next_attempt_at = ?,
                       updated_at = ?
                 WHERE published_at IS NULL
                   AND id IN (%s)
                """.formatted(placeholders);
        List<Object> args = new ArrayList<>(uniqueIds.size() + 2);
        args.add(timestamp);
        args.add(timestamp);
        args.addAll(uniqueIds);
        int rows = jdbcTemplate.update(sql, args.toArray());
        if (rows != uniqueIds.size()) {
            throw new IllegalStateException("failed to release all trading outbox event leases: expected="
                    + uniqueIds.size() + " actual=" + rows);
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

    private List<OutboxStreamBatch> toStreamBatches(List<OutboxRecord> rows) {
        Map<OutboxStreamKey, List<OutboxRecord>> grouped = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(OutboxRecord::topic)
                        .thenComparing(OutboxRecord::eventKey)
                        .thenComparingLong(OutboxRecord::id))
                .forEach(row -> grouped.computeIfAbsent(
                                new OutboxStreamKey(row.topic(), row.eventKey()), ignored -> new ArrayList<>())
                        .add(row));
        return grouped.entrySet().stream()
                .map(entry -> new OutboxStreamBatch(
                        entry.getKey().topic(), entry.getKey().eventKey(), List.copyOf(entry.getValue())))
                .toList();
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

    private void requireCompleteBatch(int[] rows, int expected, String operation) {
        if (rows == null || rows.length != expected) {
            throw new IllegalStateException("failed to write " + operation);
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("failed to write " + operation);
            }
        }
    }

    public record OrderOutboxWrite(
            String aggregateType,
            long aggregateId,
            String topic,
            String eventKey,
            String eventType,
            String payload,
            Instant createdAt) {
    }

    private record OutboxStreamKey(String topic, String eventKey) {
    }

    public record OutboxStreamBatch(String topic, String eventKey, List<OutboxRecord> rows) {

        public OutboxStreamBatch {
            rows = List.copyOf(rows);
        }
    }
}
